package com.commerce.order.infrastructure.outbox

import jakarta.persistence.*
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.LocalDateTime

/**
 * 주문 이벤트 Transactional Outbox 레코드. 주문 트랜잭션과 같은 tx에서 기록되어(원자적 캡처),
 * relay가 비동기로 Kafka 발행/직접 적용한다. payload(JSON)에 이벤트 복원에 필요한 상태를 담는다.
 */
@Entity
@Table(
    name = "order_outbox_events",
    indexes = [Index(name = "idx_order_outbox_unpublished", columnList = "published, id")],
)
class OrderOutboxEvent(
    @Column(nullable = false, unique = true, length = 36)
    val eventId: String,

    @Column(nullable = false, length = 50)
    val eventType: String,

    @Column(nullable = false, length = 30)
    val aggregateType: String,

    @Column(nullable = false)
    val aggregateId: Long,

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "json", nullable = false)
    val payload: String,

    @Column(nullable = false)
    var published: Boolean = false,

    @Column(nullable = false)
    var attempts: Int = 0,

    @Column(nullable = false, columnDefinition = "DATETIME(6)")
    val createdAt: LocalDateTime = LocalDateTime.now(),

    @Column(columnDefinition = "DATETIME(6)")
    var publishedAt: LocalDateTime? = null,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0L

    fun markPublished(at: LocalDateTime = LocalDateTime.now()) {
        published = true
        publishedAt = at
    }

    /** 전달/적용 실패 1회 기록. 반환값은 누적 시도 횟수(격리 판단용). */
    fun recordFailure(): Int {
        attempts += 1
        return attempts
    }
}
