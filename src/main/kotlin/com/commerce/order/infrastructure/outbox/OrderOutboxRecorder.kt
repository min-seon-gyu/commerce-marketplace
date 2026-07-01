package com.commerce.order.infrastructure.outbox

import com.commerce.order.domain.event.OrderEvent
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener

/**
 * 주문 이벤트를 주문 트랜잭션과 같은 tx(BEFORE_COMMIT)에서 outbox에 기록한다.
 * 같은 tx 내 INSERT이므로 "주문 커밋 ⇔ 이벤트 캡처"가 원자적이다(AFTER_COMMIT 발행 유실 문제 제거).
 * OrderEvent 하위 타입만 수신하므로 다른 도메인 이벤트는 캡처하지 않는다.
 */
@Component
class OrderOutboxRecorder(
    private val outboxRepository: OrderOutboxEventRepository,
    private val objectMapper: ObjectMapper,
) {

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    fun record(event: OrderEvent) {
        val payload = OrderEventPayload.from(event)
        outboxRepository.save(
            OrderOutboxEvent(
                eventId = event.eventId,
                eventType = event.eventType,
                aggregateType = event.aggregateType,
                aggregateId = event.aggregateId,
                payload = objectMapper.writeValueAsString(payload),
            )
        )
    }
}
