package com.commerce.order.infrastructure.outbox

import com.commerce.order.domain.event.OrderEvent
import com.commerce.order.infrastructure.log.OrderEventLog
import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonProperty
import java.math.BigDecimal
import java.time.LocalDateTime

/**
 * outbox/Kafka로 흐르는 주문 이벤트의 자기완결적 페이로드.
 * 소비자가 원본 이벤트 객체 없이도 order_event_log를 재구성할 수 있게 필요한 상태를 담는다.
 */
data class OrderEventPayload @JsonCreator constructor(
    @JsonProperty("eventId") val eventId: String,
    @JsonProperty("eventType") val eventType: String,
    @JsonProperty("orderId") val orderId: Long,
    @JsonProperty("memberId") val memberId: Long,
    @JsonProperty("totalAmount") val totalAmount: BigDecimal,
    @JsonProperty("occurredAt") val occurredAt: LocalDateTime,
) {
    fun toOrderEventLog(): OrderEventLog = OrderEventLog(
        eventId = eventId,
        eventType = eventType,
        orderId = orderId,
        memberId = memberId,
        totalAmount = totalAmount,
        createdAt = occurredAt,
    )

    companion object {
        fun from(event: OrderEvent): OrderEventPayload = OrderEventPayload(
            eventId = event.eventId,
            eventType = event.eventType,
            orderId = event.aggregateId,
            memberId = event.memberId,
            totalAmount = event.totalAmount,
            occurredAt = event.occurredAt,
        )
    }
}
