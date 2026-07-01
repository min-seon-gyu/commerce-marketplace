package com.commerce.order.infrastructure.outbox

import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.LocalDateTime

interface OrderOutboxEventRepository : JpaRepository<OrderOutboxEvent, Long> {
    /** Kafka relay: 미발행 이벤트(발행 순서). relay는 Kafka로 send만 하므로 attempts와 무관. */
    fun findTop200ByPublishedFalseOrderByIdAsc(): List<OrderOutboxEvent>

    /** Direct relay: 미발행 + 미격리(attempts < max). poison 행이 head-of-line 블로킹하지 않도록 격리분은 제외한다. */
    fun findTop200ByPublishedFalseAndAttemptsLessThanOrderByIdAsc(maxAttempts: Int): List<OrderOutboxEvent>

    /**
     * Reconciliation: 발행 완료(Kafka send)됐으나 이벤트 로그가 누락된 행(anti-join)을 직접 찾는다.
     * 격리 전(attempts < max) + 유예시간 경과(createdAt < threshold)분만 — 컨슈머 실패/DLT 유실의 회복 대상.
     */
    @Query(
        """
        select o from OrderOutboxEvent o
        where o.published = true and o.attempts < :maxAttempts and o.createdAt < :threshold
          and not exists (select l.id from OrderEventLog l where l.eventId = o.eventId)
        order by o.id asc
        """
    )
    fun findUnappliedPublished(
        @Param("maxAttempts") maxAttempts: Int,
        @Param("threshold") threshold: LocalDateTime,
        pageable: Pageable,
    ): List<OrderOutboxEvent>
}
