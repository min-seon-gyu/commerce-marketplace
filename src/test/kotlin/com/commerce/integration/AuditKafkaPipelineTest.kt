package com.commerce.integration

import com.commerce.common.audit.AuditEventPayload
import com.commerce.common.audit.AuditLogRepository
import com.commerce.common.audit.AuditSeverity
import com.commerce.common.audit.KafkaOutboxRelay
import com.commerce.common.audit.OutboxEvent
import com.commerce.common.audit.OutboxEventRepository
import com.commerce.common.audit.OutboxReconciliationScheduler
import com.commerce.member.application.MemberService
import com.commerce.support.IntegrationTestSupport
import com.commerce.support.TestFixtures
import com.fasterxml.jackson.databind.ObjectMapper
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.kafka.test.context.EmbeddedKafka
import org.springframework.test.context.TestPropertySource
import java.time.LocalDateTime

/**
 * 감사 Outbox → Kafka → Consumer → AuditLog 전체 파이프라인 E2E.
 * @EmbeddedKafka 브로커 + audit.kafka.enabled=true 로 KafkaOutboxRelay/AuditKafkaConsumer를 활성화한다.
 * (기본 통합 테스트는 audit.kafka.enabled=false 로 DirectRelay를 쓰며 브로커가 필요 없다.)
 */
@EmbeddedKafka(partitions = 1, topics = ["audit-events"], bootstrapServersProperty = "spring.kafka.bootstrap-servers")
@TestPropertySource(properties = ["audit.kafka.enabled=true"])
class AuditKafkaPipelineTest : IntegrationTestSupport() {

    @Autowired lateinit var fixtures: TestFixtures
    @Autowired lateinit var memberService: MemberService
    @Autowired lateinit var auditLogRepository: AuditLogRepository
    @Autowired lateinit var outboxRepository: OutboxEventRepository
    @Autowired lateinit var kafkaRelay: KafkaOutboxRelay
    @Autowired lateinit var reconciliationScheduler: OutboxReconciliationScheduler
    @Autowired lateinit var objectMapper: ObjectMapper

    @Test
    fun `non-critical audit flows outbox to kafka to audit log`() {
        val member = fixtures.createMember()

        memberService.suspend(member.id) // MEMBER_SUSPENDED (HIGH) → outbox (비즈니스 tx 内)

        // outbox → Kafka 발행
        kafkaRelay.relayOnce()

        // Consumer(@KafkaListener)가 비동기로 감사 로그를 기록 → 최대 20초 대기
        var audit = auditLogRepository.findByAggregateTypeAndAggregateId("MEMBER", member.id)
            .find { it.eventType == "MEMBER_SUSPENDED" }
        val deadline = System.currentTimeMillis() + 20_000
        while (audit == null && System.currentTimeMillis() < deadline) {
            Thread.sleep(200)
            audit = auditLogRepository.findByAggregateTypeAndAggregateId("MEMBER", member.id)
                .find { it.eventType == "MEMBER_SUSPENDED" }
        }

        audit.shouldNotBeNull()
        audit.severity shouldBe AuditSeverity.HIGH

        // outbox 행은 Kafka로 발행 완료 마킹됨
        val row = outboxRepository.findAll().find { it.eventType == "MEMBER_SUSPENDED" && it.aggregateId == member.id }
        row.shouldNotBeNull()
        row.published shouldBe true
    }

    /**
     * Kafka 경로는 send-ACK 시점에 published로 마킹되므로, 컨슈머 실패/DLT 유실로 감사 로그가 누락된
     * "published-but-not-applied" 행이 생길 수 있다. 재조정 스윕이 이를 anti-join으로 찾아 멱등 재적용하는지 검증한다.
     * (테스트 프로파일 audit.outbox.reconcile-grace-ms=0 → 방금 만든 행도 즉시 재조정 대상)
     */
    @Test
    fun `reconciliation re-applies a published but unapplied outbox row`() {
        val eventId = "recon-missing-1"
        val payload = AuditEventPayload(
            eventId = eventId,
            eventType = "MEMBER_SUSPENDED",
            severity = AuditSeverity.HIGH,
            aggregateType = "MEMBER",
            aggregateId = 9_100_001L,
            action = "SUSPEND",
            previousState = null,
            currentState = null,
            occurredAt = LocalDateTime.now(),
        )
        // published=true 이지만 감사 로그는 없는 행(컨슈머가 처리하지 못한 상태)을 직접 만든다.
        val row = OutboxEvent(
            eventId = eventId,
            eventType = "MEMBER_SUSPENDED",
            aggregateType = "MEMBER",
            aggregateId = 9_100_001L,
            severity = AuditSeverity.HIGH,
            payload = objectMapper.writeValueAsString(payload),
        ).apply { markPublished() }
        outboxRepository.save(row)
        auditLogRepository.existsByEventId(eventId) shouldBe false

        val recovered = reconciliationScheduler.reconcileOnce()

        (recovered >= 1) shouldBe true
        auditLogRepository.existsByEventId(eventId) shouldBe true
    }
}
