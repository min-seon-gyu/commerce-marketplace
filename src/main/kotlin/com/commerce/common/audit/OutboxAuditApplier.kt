package com.commerce.common.audit

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

/**
 * outbox 페이로드를 멱등하게 감사 로그로 적용하고, outbox 행을 발행 완료로 마킹한다.
 * Direct/Kafka relay와 Kafka consumer가 공유한다. 멱등성은 `existsByEventId`로 보장(at-least-once 안전).
 */
@Component
class OutboxAuditApplier(
    private val auditLogRepository: AuditLogRepository,
    private val outboxRepository: OutboxEventRepository,
    private val objectMapper: ObjectMapper,
) {

    /** 페이로드 JSON을 감사 로그로 적용(멱등). 중복 eventId면 무시. */
    @Transactional
    fun apply(payloadJson: String) {
        val payload = objectMapper.readValue(payloadJson, AuditEventPayload::class.java)
        if (!auditLogRepository.existsByEventId(payload.eventId)) {
            auditLogRepository.save(payload.toAuditLog())
        }
    }

    /** outbox 행을 발행 완료로 마킹(전달 메커니즘에 핸드오프됨). */
    @Transactional
    fun markPublished(outboxId: Long) {
        outboxRepository.findById(outboxId).ifPresent { it.markPublished() }
    }

    /** 전달/적용 실패를 1회 기록하고 누적 시도 횟수를 반환한다(격리 판단용). 행이 없으면 MAX(즉시 격리 취급). */
    @Transactional
    fun recordFailure(outboxId: Long): Int =
        outboxRepository.findById(outboxId).map { it.recordFailure() }.orElse(Int.MAX_VALUE)
}
