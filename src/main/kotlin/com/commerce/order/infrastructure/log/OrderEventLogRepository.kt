package com.commerce.order.infrastructure.log

import org.springframework.data.jpa.repository.JpaRepository

interface OrderEventLogRepository : JpaRepository<OrderEventLog, Long> {
    fun existsByEventId(eventId: String): Boolean
    fun findByOrderId(orderId: Long): List<OrderEventLog>
}
