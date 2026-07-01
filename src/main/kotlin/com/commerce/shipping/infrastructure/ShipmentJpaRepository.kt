package com.commerce.shipping.infrastructure

import com.commerce.shipping.domain.Shipment
import org.springframework.data.jpa.repository.JpaRepository

interface ShipmentJpaRepository : JpaRepository<Shipment, Long> {
    fun findByOrderId(orderId: Long): Shipment?
}
