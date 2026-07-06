package com.commerce.promotion.infrastructure

import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * 프로모션 예산 Redis 카운터를 DB 진실원천(비취소 쿠폰 주문의 discount_amount 합)으로 주기 재동기화한다.
 *
 * Redis 재시작/키 유실이나 드리프트로 카운터가 실제 소비와 어긋나면 예산 상한이 조기 소진되거나
 * 반대로 초과 발급될 수 있으므로 DB 기준으로 보정한다. 재설정(SET)은 멱등적이라 다중 인스턴스에서도
 * 분산 락 없이 안전하다.
 */
@Component
class PromotionBudgetResyncScheduler(
    private val promotionRepository: PromotionJpaRepository,
    private val couponRepository: CouponJpaRepository,
    private val budgetManager: PromotionBudgetManager,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelayString = "\${promotion.budget.resync-ms:300000}")
    fun resyncAll() {
        val promotions = promotionRepository.findAll()
        promotions.forEach { promotion ->
            val consumed = couponRepository.sumConsumedBudgetByPromotion(promotion.id).toLong()
            budgetManager.resync(promotion.id, consumed)
        }
        if (promotions.isNotEmpty()) log.debug("Resynced {} promotion budget counters from DB", promotions.size)
    }
}
