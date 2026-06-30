package com.commerce.common.audit

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.data.domain.PageRequest
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.LocalDateTime

/**
 * outbox 재조정(reconciliation). Kafka 경로는 send-ACK 시점에 published로 마킹되므로, 컨슈머 실패(+DLT 유실 등)로
 * 감사 로그가 누락된 "published-but-not-applied" 행이 생길 수 있다. 이를 주기적으로 anti-join으로 찾아 멱등 재적용한다.
 * (Kafka 활성 시에만 동작 — Direct 경로는 자체 attempts/격리로 자기완결적.)
 */
@Component
@ConditionalOnProperty(prefix = "audit.kafka", name = ["enabled"], havingValue = "true")
class OutboxReconciliationScheduler(
    private val outboxRepository: OutboxEventRepository,
    private val applier: OutboxAuditApplier,
    @Value("\${audit.outbox.max-attempts:5}") private val maxAttempts: Int,
    @Value("\${audit.outbox.reconcile-grace-ms:600000}") private val graceMs: Long,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelayString = "\${audit.outbox.reconcile-ms:300000}")
    fun reconcile() {
        reconcileOnce()
    }

    /** 적용 누락된 발행 행을 재적용한다. 회복 건수 반환(테스트에서 수동 호출). */
    fun reconcileOnce(): Int {
        val threshold = LocalDateTime.now().minus(Duration.ofMillis(graceMs))
        var recovered = 0
        outboxRepository.findUnappliedPublished(maxAttempts, threshold, PageRequest.of(0, 200)).forEach { event ->
            try {
                applier.apply(event.payload) // 멱등 — 동시 적용돼도 안전
                recovered++
                log.info("Reconciled missing audit for outbox {} ({})", event.id, event.eventType)
            } catch (e: Exception) {
                val attempts = applier.recordFailure(event.id)
                log.warn("Reconcile re-apply failed for outbox {} ({}), attempt {}: {}", event.id, event.eventType, attempts, e.message)
            }
        }
        return recovered
    }
}
