package com.commerce.order.infrastructure.log

import jakarta.persistence.*
import java.math.BigDecimal
import java.time.LocalDateTime

/**
 * 주문 이벤트 로그(읽기 프로젝션). Kafka 소비자가 멱등하게 기록하는 append-only 이벤트 이력.
 * (구 AuditLog를 대체 — 등급/before-after 상태 없이 주문 이벤트 이력에 특화.)
 */
@Entity
@Table(
    name = "order_event_log",
    indexes = [
        Index(name = "idx_oel_event_type", columnList = "eventType, createdAt"),
        Index(name = "idx_oel_order", columnList = "orderId, createdAt"),
    ],
)
class OrderEventLog(
    @Column(nullable = false, unique = true, length = 36)
    val eventId: String,

    @Column(nullable = false, length = 50)
    val eventType: String,

    @Column(nullable = false)
    val orderId: Long,

    @Column(nullable = false)
    val memberId: Long,

    @Column(nullable = false, precision = 38, scale = 2)
    val totalAmount: BigDecimal,

    @Column(nullable = false, columnDefinition = "DATETIME(6)")
    val createdAt: LocalDateTime = LocalDateTime.now(),
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0L
}
