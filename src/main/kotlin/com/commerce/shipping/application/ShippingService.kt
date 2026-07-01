package com.commerce.shipping.application

import com.commerce.common.exception.BusinessException
import com.commerce.common.exception.ErrorCode
import com.commerce.shipping.domain.Shipment
import com.commerce.shipping.domain.ShipmentStatus
import com.commerce.shipping.infrastructure.ShipmentJpaRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

/**
 * 배송 상태 관리. 결제 완료 시 [createForOrder]로 PREPARING 배송을 만들고, 운영자가 ship/deliver로 전이한다.
 * 반품 클레임은 [isDelivered]를 게이트로 사용한다.
 */
@Service
class ShippingService(
    private val shipmentRepository: ShipmentJpaRepository,
) {

    /** 주문 결제 트랜잭션 내부에서 PREPARING 배송을 생성한다(주문과 원자적). */
    @Transactional(propagation = Propagation.MANDATORY)
    fun createForOrder(orderId: Long): Shipment =
        shipmentRepository.save(Shipment(orderId = orderId))

    @Transactional
    fun ship(orderId: Long, courier: String, trackingNumber: String): Shipment {
        val shipment = getByOrderId(orderId)
        shipment.ship(courier, trackingNumber)
        return shipment
    }

    @Transactional
    fun deliver(orderId: Long): Shipment {
        val shipment = getByOrderId(orderId)
        shipment.deliver()
        return shipment
    }

    @Transactional(readOnly = true)
    fun getByOrderId(orderId: Long): Shipment =
        shipmentRepository.findByOrderId(orderId)
            ?: throw BusinessException(ErrorCode.SHIPMENT_NOT_FOUND)

    @Transactional(readOnly = true)
    fun isDelivered(orderId: Long): Boolean = getByOrderId(orderId).isDelivered()

    /** 발송된 이후(SHIPPED/DELIVERED)면 true. 배송 정보가 없으면 발송 전으로 간주(false). 주문 취소 게이트용. */
    @Transactional(readOnly = true)
    fun isShipped(orderId: Long): Boolean =
        shipmentRepository.findByOrderId(orderId)?.let { it.status != ShipmentStatus.PREPARING } ?: false
}
