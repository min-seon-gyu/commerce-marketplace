package com.commerce.integration

import com.commerce.order.infrastructure.log.OrderEventLogRepository
import com.commerce.order.infrastructure.outbox.DirectOrderEventRelay
import com.commerce.order.infrastructure.outbox.OrderEventPayload
import com.commerce.order.infrastructure.outbox.OrderOutboxEvent
import com.commerce.order.infrastructure.outbox.OrderOutboxEventRepository
import com.commerce.support.IntegrationTestSupport
import com.fasterxml.jackson.databind.ObjectMapper
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.math.BigDecimal
import java.time.LocalDateTime

/**
 * Direct relay의 poison 가드 검증: 적용이 영구 실패하는 outbox 행은 max-attempts 초과 시 격리(published 마킹)되어
 * 무한 재처리가 멈추고, id 오름차순 폴링에서 유효한 후속 행을 head-of-line 블로킹하지 않는다.
 * (테스트 프로파일 order.outbox.max-attempts=3)
 */
class DirectRelayQuarantineTest : IntegrationTestSupport() {

    @Autowired lateinit var directRelay: DirectOrderEventRelay
    @Autowired lateinit var outboxRepository: OrderOutboxEventRepository
    @Autowired lateinit var orderEventLogRepository: OrderEventLogRepository
    @Autowired lateinit var objectMapper: ObjectMapper

    @Test
    fun `poison row is quarantined after max attempts without blocking a valid row`() {
        // poison: 유효 JSON이지만 OrderEventPayload로 역직렬화 불가(필수 필드 없음) → apply 영구 실패
        val poison = outboxRepository.save(
            OrderOutboxEvent(
                eventId = "poison-quarantine-1",
                eventType = "ORDER_PLACED",
                aggregateType = "ORDER",
                aggregateId = 9_000_001L,
                payload = "{}",
            )
        )
        // valid: 정상 페이로드 — poison보다 뒤(id 큰) 순서. poison에 막히지 않고 적용돼야 한다.
        val validPayload = OrderEventPayload(
            eventId = "valid-after-poison-1",
            eventType = "ORDER_PLACED",
            orderId = 9_000_002L,
            memberId = 9_300_002L,
            totalAmount = BigDecimal("5000"),
            occurredAt = LocalDateTime.now(),
        )
        val valid = outboxRepository.save(
            OrderOutboxEvent(
                eventId = "valid-after-poison-1",
                eventType = "ORDER_PLACED",
                aggregateType = "ORDER",
                aggregateId = 9_000_002L,
                payload = objectMapper.writeValueAsString(validPayload),
            )
        )

        // max-attempts(=3)만큼 relay 반복: 매 회차 poison은 실패하며 attempts 누적, valid는 1회차에 적용된다.
        repeat(3) { directRelay.relayOnce() }

        // poison: max-attempts 도달 → 격리(published 마킹), 이벤트 로그는 생성되지 않음(운영 모니터링 대상)
        val poisonRow = outboxRepository.findById(poison.id).get()
        poisonRow.attempts shouldBe 3
        poisonRow.published shouldBe true
        orderEventLogRepository.existsByEventId("poison-quarantine-1") shouldBe false

        // valid: poison에 블로킹되지 않고 정상 적용·발행
        val validRow = outboxRepository.findById(valid.id).get()
        validRow.published shouldBe true
        orderEventLogRepository.existsByEventId("valid-after-poison-1") shouldBe true
    }
}
