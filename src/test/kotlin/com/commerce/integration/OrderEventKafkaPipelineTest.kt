package com.commerce.integration

import com.commerce.order.infrastructure.log.OrderEventLogRepository
import com.commerce.order.infrastructure.outbox.KafkaOrderEventRelay
import com.commerce.order.infrastructure.outbox.OrderEventPayload
import com.commerce.order.infrastructure.outbox.OrderEventReconciliationScheduler
import com.commerce.order.infrastructure.outbox.OrderOutboxEvent
import com.commerce.order.infrastructure.outbox.OrderOutboxEventRepository
import com.commerce.support.IntegrationTestSupport
import com.commerce.support.TestFixtures
import com.fasterxml.jackson.databind.ObjectMapper
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.kafka.test.context.EmbeddedKafka
import org.springframework.test.context.TestPropertySource
import java.math.BigDecimal
import java.time.LocalDateTime

/**
 * 주문 이벤트 Outbox → Kafka → Consumer → order_event_log 전체 파이프라인 E2E.
 * @EmbeddedKafka 브로커 + order.kafka.enabled=true 로 KafkaOrderEventRelay/OrderEventConsumer를 활성화한다.
 * (기본 통합 테스트는 order.kafka.enabled=false 로 DirectOrderEventRelay를 쓰며 브로커가 필요 없다.)
 */
@EmbeddedKafka(partitions = 1, topics = ["order-events"], bootstrapServersProperty = "spring.kafka.bootstrap-servers")
@TestPropertySource(properties = ["order.kafka.enabled=true"])
class OrderEventKafkaPipelineTest : IntegrationTestSupport() {

    @Autowired lateinit var fixtures: TestFixtures
    @Autowired lateinit var orderEventLogRepository: OrderEventLogRepository
    @Autowired lateinit var outboxRepository: OrderOutboxEventRepository
    @Autowired lateinit var kafkaRelay: KafkaOrderEventRelay
    @Autowired lateinit var reconciliationScheduler: OrderEventReconciliationScheduler
    @Autowired lateinit var objectMapper: ObjectMapper

    @Test
    fun `order placed event flows outbox to kafka to order event log`() {
        val seller = fixtures.createSeller(fixtures.createMember())
        val buyer = fixtures.createMember()

        val order = fixtures.sellerSale(buyer.id, seller.id, BigDecimal("10000")) // → ORDER_PLACED (outbox 캡처)

        // outbox → Kafka 발행
        kafkaRelay.relayOnce()

        // Consumer(@KafkaListener)가 비동기로 order_event_log를 기록 → 최대 20초 대기
        var log = orderEventLogRepository.findByOrderId(order.id).find { it.eventType == "ORDER_PLACED" }
        val deadline = System.currentTimeMillis() + 20_000
        while (log == null && System.currentTimeMillis() < deadline) {
            Thread.sleep(200)
            log = orderEventLogRepository.findByOrderId(order.id).find { it.eventType == "ORDER_PLACED" }
        }

        log.shouldNotBeNull()
        log.totalAmount.compareTo(BigDecimal("10000")) shouldBe 0

        // outbox 행은 Kafka로 발행 완료 마킹됨
        val row = outboxRepository.findAll().find { it.eventType == "ORDER_PLACED" && it.aggregateId == order.id }
        row.shouldNotBeNull()
        row.published shouldBe true
    }

    /**
     * Kafka 경로는 send-ACK 시점에 published로 마킹되므로, 컨슈머 실패/DLT 유실로 이벤트 로그가 누락된
     * "published-but-not-applied" 행이 생길 수 있다. 재조정 스윕이 이를 anti-join으로 찾아 멱등 재적용하는지 검증한다.
     * (테스트 프로파일 order.outbox.reconcile-grace-ms=0 → 방금 만든 행도 즉시 재조정 대상)
     */
    @Test
    fun `reconciliation re-applies a published but unapplied outbox row`() {
        val eventId = "recon-missing-order-1"
        val payload = OrderEventPayload(
            eventId = eventId,
            eventType = "ORDER_PLACED",
            orderId = 9_100_001L,
            memberId = 9_200_001L,
            totalAmount = BigDecimal("5000"),
            occurredAt = LocalDateTime.now(),
        )
        // published=true 이지만 이벤트 로그는 없는 행(컨슈머가 처리하지 못한 상태)을 직접 만든다.
        val row = OrderOutboxEvent(
            eventId = eventId,
            eventType = "ORDER_PLACED",
            aggregateType = "ORDER",
            aggregateId = 9_100_001L,
            payload = objectMapper.writeValueAsString(payload),
        ).apply { markPublished() }
        outboxRepository.save(row)
        orderEventLogRepository.existsByEventId(eventId) shouldBe false

        val recovered = reconciliationScheduler.reconcileOnce()

        (recovered >= 1) shouldBe true
        orderEventLogRepository.existsByEventId(eventId) shouldBe true
    }
}
