package com.commerce.integration

import com.commerce.product.interfaces.ProductDetailCache
import com.commerce.product.interfaces.ProductDetailResponse
import com.commerce.support.IntegrationTestSupport
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.data.redis.core.StringRedisTemplate

/**
 * 상품 상세 캐시의 회복력·동작 검증.
 * 핵심: Redis 장애·역직렬화 실패 시 예외 없이 null(→ DB 폴백)을 반환해 읽기 가용성을 유지한다.
 * (실제 상품 id와 겹치지 않도록 높은 id를 쓴다 — 공유 Redis 컨테이너에서 키 충돌 방지.)
 */
class ProductDetailCacheTest : IntegrationTestSupport() {

    @Autowired lateinit var cache: ProductDetailCache
    @Autowired lateinit var redisTemplate: StringRedisTemplate

    private val base = 90_000_000L

    private fun sample(id: Long) = ProductDetailResponse(
        id = id, sellerId = 1L, name = "상품$id", description = null,
        category = "OTHER", status = "ON_SALE", skus = emptyList(),
    )

    @Test
    fun `put then get returns the cached response`() {
        val id = base + 1
        cache.put(id, sample(id))

        val cached = cache.get(id)
        (cached != null) shouldBe true
        cached!!.name shouldBe "상품$id"
    }

    @Test
    fun `evict removes the cached entry`() {
        val id = base + 2
        cache.put(id, sample(id))
        cache.evict(id)

        cache.get(id) shouldBe null
    }

    @Test
    fun `corrupt cached json falls back to null instead of throwing`() {
        val id = base + 3
        // 스키마 불일치/깨진 JSON을 직접 심어도 get은 예외 없이 null(→ DB 폴백)을 반환해야 한다.
        redisTemplate.opsForValue().set("product:detail:$id", "{not-valid-json")

        cache.get(id) shouldBe null
    }

    @Test
    fun `get on empty cache returns null`() {
        cache.get(base + 4) shouldBe null
    }
}
