package com.commerce.product.interfaces

import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component
import java.time.Duration
import java.util.concurrent.ThreadLocalRandom

/**
 * 상품 상세 응답의 Redis 캐시-어사이드.
 *
 * Redis는 **best-effort**로 다룬다 — 조회/저장/무효화 실패는 로그만 남기고 DB 경로로 강등해 읽기
 * 가용성을 유지한다(캐시가 가용성을 떨어뜨리지 않게 한다). 같은 저장소의 `IdempotencyStore.cacheQuietly`와
 * 동일한 규율이다. TTL에 지터를 주어 동일 키의 동시 만료로 인한 캐시 스탬피드를 완화한다.
 *
 * 단순 문자열 get/set만 필요하므로 [StringRedisTemplate]을 쓴다(Redisson 커넥션 팩토리 위에서 동작 —
 * 별도 커넥션 풀 없음). 락·Lua 등 고수준 기능이 필요한 곳만 RedissonClient를 직접 쓴다.
 */
@Component
class ProductDetailCache(
    private val redisTemplate: StringRedisTemplate,
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    private fun key(id: Long) = "product:detail:$id"

    /** 캐시 조회. Redis 장애·역직렬화 실패(스키마 변경 등) 시 null을 반환해 호출자가 DB로 폴백하게 한다. */
    fun get(id: Long): ProductDetailResponse? =
        try {
            redisTemplate.opsForValue().get(key(id))
                ?.let { objectMapper.readValue(it, ProductDetailResponse::class.java) }
        } catch (e: Exception) {
            log.warn("Product detail cache read failed for {}: {}", id, e.message)
            null
        }

    /** 캐시 저장(TTL 25~35s 지터로 동시 만료 스탬피드 완화). 실패는 무시한다. */
    fun put(id: Long, response: ProductDetailResponse) {
        try {
            val ttl = Duration.ofSeconds(ThreadLocalRandom.current().nextLong(25, 36))
            redisTemplate.opsForValue().set(key(id), objectMapper.writeValueAsString(response), ttl)
        } catch (e: Exception) {
            log.warn("Product detail cache write failed for {}: {}", id, e.message)
        }
    }

    /** 캐시 무효화. 실패는 무시한다(DB는 이미 갱신됐으므로 응답에 영향 없음). */
    fun evict(id: Long) {
        try {
            redisTemplate.delete(key(id))
        } catch (e: Exception) {
            log.warn("Product detail cache evict failed for {}: {}", id, e.message)
        }
    }
}
