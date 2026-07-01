package com.commerce.shipping.domain

import com.commerce.common.domain.BaseEntity
import com.commerce.common.exception.BusinessException
import com.commerce.common.exception.ErrorCode
import jakarta.persistence.*
import java.time.LocalDateTime

enum class ShipmentStatus { PREPARING, SHIPPED, DELIVERED }

/**
 * 배송(주문당 1건). 결제 완료 시 PREPARING으로 생성되고, 운영자가 SHIPPED→DELIVERED로 전이한다.
 * 반품 클레임은 DELIVERED 이후에만 요청할 수 있어 이 상태가 반품 게이트 역할을 한다.
 */
@Entity
@Table(name = "shipments", indexes = [Index(name = "uq_shipment_order", columnList = "order_id", unique = true)])
class Shipment(
    @Column(name = "order_id", nullable = false)
    val orderId: Long,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var status: ShipmentStatus = ShipmentStatus.PREPARING,

    @Column(length = 50)
    var courier: String? = null,

    @Column(name = "tracking_number", length = 100)
    var trackingNumber: String? = null,

    @Column(name = "shipped_at")
    var shippedAt: LocalDateTime? = null,

    @Column(name = "delivered_at")
    var deliveredAt: LocalDateTime? = null,
) : BaseEntity() {

    fun ship(courier: String, trackingNumber: String) {
        if (status != ShipmentStatus.PREPARING)
            throw BusinessException(ErrorCode.INVALID_STATE_TRANSITION, "PREPARING 상태에서만 발송할 수 있습니다")
        status = ShipmentStatus.SHIPPED
        this.courier = courier
        this.trackingNumber = trackingNumber
        shippedAt = LocalDateTime.now()
    }

    fun deliver() {
        if (status != ShipmentStatus.SHIPPED)
            throw BusinessException(ErrorCode.INVALID_STATE_TRANSITION, "SHIPPED 상태에서만 배송완료 처리할 수 있습니다")
        status = ShipmentStatus.DELIVERED
        deliveredAt = LocalDateTime.now()
    }

    fun isDelivered(): Boolean = status == ShipmentStatus.DELIVERED
}
