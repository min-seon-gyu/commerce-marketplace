package com.commerce.promotion.infrastructure.ai

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * 의존성 없는 간이 서킷브레이커(스레드 안전).
 * 연속 실패가 failureThreshold 에 도달하면 openMillis 동안 오픈(빠른 실패)하고,
 * 그 시간이 지나면 단일 시도를 허용(half-open)한다. 성공 시 카운트 초기화.
 */
class SimpleCircuitBreaker(
    private val failureThreshold: Int,
    private val openMillis: Long,
) {
    private val consecutiveFailures = AtomicInteger(0)
    private val openUntilEpochMs = AtomicLong(0)

    fun allowRequest(): Boolean = System.currentTimeMillis() >= openUntilEpochMs.get()

    fun recordSuccess() {
        consecutiveFailures.set(0)
        openUntilEpochMs.set(0)
    }

    fun recordFailure() {
        if (consecutiveFailures.incrementAndGet() >= failureThreshold) {
            openUntilEpochMs.set(System.currentTimeMillis() + openMillis)
        }
    }
}
