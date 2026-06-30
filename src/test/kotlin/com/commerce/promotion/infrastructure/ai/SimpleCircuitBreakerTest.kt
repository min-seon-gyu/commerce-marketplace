package com.commerce.promotion.infrastructure.ai

import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import org.junit.jupiter.api.Test

class SimpleCircuitBreakerTest {

    @Test
    fun `연속 실패가 임계치에 도달하면 오픈된다`() {
        val cb = SimpleCircuitBreaker(failureThreshold = 3, openMillis = 10_000)
        cb.allowRequest().shouldBeTrue()
        repeat(3) { cb.recordFailure() }
        cb.allowRequest().shouldBeFalse() // 오픈 → 빠른 실패
    }

    @Test
    fun `오픈 유지 시간이 지나면 다시 허용된다(half-open)`() {
        val cb = SimpleCircuitBreaker(failureThreshold = 1, openMillis = 0) // 즉시 재허용
        cb.recordFailure()
        cb.allowRequest().shouldBeTrue()
    }

    @Test
    fun `성공하면 실패 카운트가 초기화된다`() {
        val cb = SimpleCircuitBreaker(failureThreshold = 2, openMillis = 10_000)
        cb.recordFailure()
        cb.recordSuccess()
        cb.recordFailure()
        cb.allowRequest().shouldBeTrue() // 임계치 미달
    }
}
