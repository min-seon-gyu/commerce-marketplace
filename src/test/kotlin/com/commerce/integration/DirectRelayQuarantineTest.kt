package com.commerce.integration

import com.commerce.common.audit.AuditEventPayload
import com.commerce.common.audit.AuditLogRepository
import com.commerce.common.audit.AuditSeverity
import com.commerce.common.audit.DirectOutboxRelay
import com.commerce.common.audit.OutboxEvent
import com.commerce.common.audit.OutboxEventRepository
import com.commerce.support.IntegrationTestSupport
import com.fasterxml.jackson.databind.ObjectMapper
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.time.LocalDateTime

/**
 * Direct relay의 poison 가드 검증: 적용이 영구 실패하는 outbox 행은 max-attempts 초과 시 격리(published 마킹)되어
 * 무한 재처리가 멈추고, id 오름차순 폴링에서 유효한 후속 행을 head-of-line 블로킹하지 않는다.
 * (테스트 프로파일 audit.outbox.max-attempts=3)
 */
class DirectRelayQuarantineTest : IntegrationTestSupport() {

    @Autowired lateinit var directRelay: DirectOutboxRelay
    @Autowired lateinit var outboxRepository: OutboxEventRepository
    @Autowired lateinit var auditLogRepository: AuditLogRepository
    @Autowired lateinit var objectMapper: ObjectMapper

    @Test
    fun `poison row is quarantined after max attempts without blocking a valid row`() {
        // poison: 유효 JSON이지만 AuditEventPayload로 역직렬화 불가(필수 필드 없음) → apply 영구 실패
        val poison = outboxRepository.save(
            OutboxEvent(
                eventId = "poison-quarantine-1",
                eventType = "MEMBER_SUSPENDED",
                aggregateType = "MEMBER",
                aggregateId = 9_000_001L,
                severity = AuditSeverity.HIGH,
                payload = "{}",
            )
        )
        // valid: 정상 페이로드 — poison보다 뒤(id 큰) 순서. poison에 막히지 않고 적용돼야 한다.
        val validPayload = AuditEventPayload(
            eventId = "valid-after-poison-1",
            eventType = "MEMBER_SUSPENDED",
            severity = AuditSeverity.HIGH,
            aggregateType = "MEMBER",
            aggregateId = 9_000_002L,
            action = "SUSPEND",
            previousState = null,
            currentState = null,
            occurredAt = LocalDateTime.now(),
        )
        val valid = outboxRepository.save(
            OutboxEvent(
                eventId = "valid-after-poison-1",
                eventType = "MEMBER_SUSPENDED",
                aggregateType = "MEMBER",
                aggregateId = 9_000_002L,
                severity = AuditSeverity.HIGH,
                payload = objectMapper.writeValueAsString(validPayload),
            )
        )

        // max-attempts(=3)만큼 relay 반복: 매 회차 poison은 실패하며 attempts 누적, valid는 1회차에 적용된다.
        repeat(3) { directRelay.relayOnce() }

        // poison: max-attempts 도달 → 격리(published 마킹), 감사 로그는 생성되지 않음(운영 모니터링 대상)
        val poisonRow = outboxRepository.findById(poison.id).get()
        poisonRow.attempts shouldBe 3
        poisonRow.published shouldBe true
        auditLogRepository.existsByEventId("poison-quarantine-1") shouldBe false

        // valid: poison에 블로킹되지 않고 정상 적용·발행
        val validRow = outboxRepository.findById(valid.id).get()
        validRow.published shouldBe true
        auditLogRepository.existsByEventId("valid-after-poison-1") shouldBe true
    }
}
