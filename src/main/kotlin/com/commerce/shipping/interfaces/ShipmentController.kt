package com.commerce.shipping.interfaces

import com.commerce.common.api.ApiResponse
import com.commerce.common.exception.BusinessException
import com.commerce.common.exception.ErrorCode
import com.commerce.common.security.SecurityUtils
import com.commerce.order.application.OrderService
import com.commerce.shipping.application.ShippingService
import com.commerce.shipping.domain.Shipment
import org.springframework.web.bind.annotation.*
import java.time.LocalDateTime

data class ShipRequest(val courier: String, val trackingNumber: String)

data class ShipmentResponse(
    val orderId: Long,
    val status: String,
    val courier: String?,
    val trackingNumber: String?,
    val shippedAt: LocalDateTime?,
    val deliveredAt: LocalDateTime?,
) {
    companion object {
        fun from(s: Shipment) = ShipmentResponse(s.orderId, s.status.name, s.courier, s.trackingNumber, s.shippedAt, s.deliveredAt)
    }
}

/** 배송 조회(본인 주문/ADMIN). 운영 전이(발송/배송완료)는 [AdminShipmentController]에서 ADMIN 게이트된다. */
@RestController
@RequestMapping("/api/v1/shipments")
class ShipmentController(
    private val shippingService: ShippingService,
    private val orderService: OrderService,
) {

    @GetMapping("/{orderId}")
    fun get(@PathVariable orderId: Long): ApiResponse<ShipmentResponse> {
        val order = orderService.getDetail(orderId).order
        if (!SecurityUtils.isAdmin() && order.memberId != SecurityUtils.currentMemberId())
            throw BusinessException(ErrorCode.ACCESS_DENIED)
        return ApiResponse.ok(ShipmentResponse.from(shippingService.getByOrderId(orderId)))
    }
}

/** 배송 운영(관리자). 경로가 `/api/v1/admin` 하위이므로 SecurityConfig에서 ADMIN 게이트된다. */
@RestController
@RequestMapping("/api/v1/admin/shipments")
class AdminShipmentController(
    private val shippingService: ShippingService,
) {

    @PostMapping("/{orderId}/ship")
    fun ship(@PathVariable orderId: Long, @RequestBody request: ShipRequest): ApiResponse<ShipmentResponse> =
        ApiResponse.ok(ShipmentResponse.from(shippingService.ship(orderId, request.courier, request.trackingNumber)))

    @PostMapping("/{orderId}/deliver")
    fun deliver(@PathVariable orderId: Long): ApiResponse<ShipmentResponse> =
        ApiResponse.ok(ShipmentResponse.from(shippingService.deliver(orderId)))
}
