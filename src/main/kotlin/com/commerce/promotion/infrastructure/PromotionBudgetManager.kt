package com.commerce.promotion.infrastructure

import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.DefaultRedisScript
import org.springframework.stereotype.Component
import java.math.BigDecimal

/**
 * 프로모션 예산을 Redis 원자 카운터로 제어한다.
 * - reserve: Lua로 INCRBY + 한도 검증을 원자 수행. 초과 시 자동 롤백 후 false.
 * - release: Lua로 DECRBY + 하한 0 클램프(보상/반환·결제 취소 시 예산 반환).
 * DB 진실원천은 비취소 쿠폰 주문의 discount_amount 합계이며,
 * [PromotionBudgetResyncScheduler]가 주기적으로 Redis 카운터를 그 값으로 재동기화한다(유실/드리프트 보정).
 *
 * 스크립트 실행은 [StringRedisTemplate.execute]를 쓴다 — Spring의 스크립트 실행기가 EVALSHA(스크립트
 * SHA1만 전송, 서버 캐시 실행)를 우선 시도하고 NOSCRIPT면 EVAL로 전문을 로드하므로, 사실상 첫 1회만
 * 스크립트 전문이 전송된다.
 */
@Component
class PromotionBudgetManager(
    private val redisTemplate: StringRedisTemplate,
) {

    /** 원자 예약. 예약 후 누적이 한도 이내면 true, 초과면 롤백 후 false. */
    fun reserve(promotionId: Long, discount: BigDecimal, budgetLimit: BigDecimal): Boolean {
        val result = redisTemplate.execute(
            RESERVE_SCRIPT,
            listOf(budgetKey(promotionId)),
            discount.longValueExact().toString(), budgetLimit.longValueExact().toString(),
        )
        return result != -1L
    }

    /** 보상/반환: 소비된 예산을 되돌린다(원자 DECRBY + 하한 0 클램프로 음수 카운터 방지). */
    fun release(promotionId: Long, discount: BigDecimal) {
        redisTemplate.execute(
            RELEASE_SCRIPT,
            listOf(budgetKey(promotionId)),
            discount.longValueExact().toString(),
        )
    }

    /**
     * DB(비취소 쿠폰 주문의 discount_amount 합)를 진실원천으로 Redis 예산 카운터를 재설정한다.
     * Redis 유실/드리프트를 [PromotionBudgetResyncScheduler]가 주기적으로 이 메서드로 보정한다.
     */
    fun resync(promotionId: Long, dbConsumed: Long) {
        redisTemplate.opsForValue().set(budgetKey(promotionId), dbConsumed.toString())
    }

    /** 현재 소비된 예산(원). */
    fun consumed(promotionId: Long): Long =
        redisTemplate.opsForValue().get(budgetKey(promotionId))?.toLong() ?: 0L

    private fun budgetKey(promotionId: Long) = "promotion:budget:$promotionId"

    companion object {
        /**
         * KEYS[1]=예산 키, ARGV[1]=증가량(할인액), ARGV[2]=예산 한도.
         * 반환: 성공 시 새 누적, 한도 초과 시 -1(롤백 후).
         */
        private val RESERVE_SCRIPT = DefaultRedisScript(
            """
            local current = redis.call('INCRBY', KEYS[1], ARGV[1])
            if current > tonumber(ARGV[2]) then
                redis.call('DECRBY', KEYS[1], ARGV[1])
                return -1
            end
            return current
            """.trimIndent(),
            Long::class.java,
        )

        /** KEYS[1]=예산 키, ARGV[1]=반환액. DECRBY 후 음수면 0으로 클램프. 반환: 새 누적(≥0). */
        private val RELEASE_SCRIPT = DefaultRedisScript(
            """
            local v = redis.call('DECRBY', KEYS[1], ARGV[1])
            if v < 0 then
                redis.call('SET', KEYS[1], 0)
                return 0
            end
            return v
            """.trimIndent(),
            Long::class.java,
        )
    }
}
