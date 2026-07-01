package com.commerce.integration

import com.commerce.cart.application.CartService
import com.commerce.claim.application.ReturnClaimService
import com.commerce.claim.domain.ReturnClaimStatus
import com.commerce.claim.domain.ReturnReason
import com.commerce.common.exception.BusinessException
import com.commerce.common.exception.ErrorCode
import com.commerce.inventory.application.StockService
import com.commerce.ledger.application.LedgerVerificationService
import com.commerce.order.application.OrderService
import com.commerce.order.domain.OrderStatus
import com.commerce.order.infrastructure.OrderLineJpaRepository
import com.commerce.shipping.application.ShippingService
import com.commerce.shipping.domain.ShipmentStatus
import com.commerce.support.IntegrationTestSupport
import com.commerce.support.TestFixtures
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.math.BigDecimal

/**
 * 배송 + 반품 클레임 워크플로우. 결제 → 발송 → 배송완료 → 반품요청 → 승인(환불) 흐름과 게이트/상태 전이 검증.
 */
class ReturnClaimFlowTest : IntegrationTestSupport() {

    @Autowired lateinit var fixtures: TestFixtures
    @Autowired lateinit var orderService: OrderService
    @Autowired lateinit var cartService: CartService
    @Autowired lateinit var shippingService: ShippingService
    @Autowired lateinit var returnClaimService: ReturnClaimService
    @Autowired lateinit var orderLineRepository: OrderLineJpaRepository
    @Autowired lateinit var stockService: StockService
    @Autowired lateinit var verificationService: LedgerVerificationService

    /** 한 판매자·두 SKU 주문 1건(결제 완료). (buyerId, orderId, sku 쌍) 반환. */
    private fun placeTwoLineOrder(p1: BigDecimal, p2: BigDecimal): Triple<Long, Long, Pair<Long, Long>> {
        val owner = fixtures.createMember()
        val seller = fixtures.createSeller(owner)
        val buyer = fixtures.createMember()
        val sku1 = fixtures.createOnSaleSku(seller.id, p1, 100)
        val sku2 = fixtures.createOnSaleSku(seller.id, p2, 100)
        cartService.addItem(buyer.id, sku1, 1)
        cartService.addItem(buyer.id, sku2, 1)
        val order = orderService.placeOrder(buyer.id)
        return Triple(buyer.id, order.id, sku1 to sku2)
    }

    private fun deliver(orderId: Long) {
        shippingService.ship(orderId, "CJ", "TRACK-$orderId")
        shippingService.deliver(orderId)
    }

    @Test
    fun `place ships delivers then approved return refunds the line`() {
        val (buyerId, orderId, skus) = placeTwoLineOrder(BigDecimal("30000"), BigDecimal("20000"))
        val (sku1, sku2) = skus

        // 결제 시 배송 PREPARING 자동 생성
        shippingService.getByOrderId(orderId).status shouldBe ShipmentStatus.PREPARING
        deliver(orderId)
        shippingService.getByOrderId(orderId).status shouldBe ShipmentStatus.DELIVERED

        val line1 = orderLineRepository.findByOrderId(orderId).first { it.skuId == sku1 }
        val claim = returnClaimService.requestReturn(buyerId, orderId, listOf(line1.id), ReturnReason.DEFECTIVE, "불량")
        returnClaimService.getDetail(claim.id).claim.status shouldBe ReturnClaimStatus.REQUESTED

        returnClaimService.approveReturn(claim.id)

        // 승인 → 환불 + 클레임 완료(원자적)
        returnClaimService.getDetail(claim.id).claim.status shouldBe ReturnClaimStatus.COMPLETED
        orderLineRepository.findByOrderId(orderId).first { it.id == line1.id }.refunded.shouldBeTrue()
        stockService.getBySkuId(sku1).quantity shouldBe 100 // 복원
        stockService.getBySkuId(sku2).quantity shouldBe 99  // 유지
        orderService.getDetail(orderId).order.status shouldBe OrderStatus.PARTIALLY_REFUNDED
        verificationService.verify().isBalanced.shouldBeTrue()

        // 나머지 라인까지 반품·승인 → 전액 환불
        val line2 = orderLineRepository.findByOrderId(orderId).first { it.skuId == sku2 }
        val claim2 = returnClaimService.requestReturn(buyerId, orderId, listOf(line2.id), ReturnReason.CHANGED_MIND)
        returnClaimService.approveReturn(claim2.id)
        orderService.getDetail(orderId).order.status shouldBe OrderStatus.REFUNDED
        verificationService.verify().isBalanced.shouldBeTrue()
    }

    @Test
    fun `return is rejected before delivery`() {
        val (buyerId, orderId, skus) = placeTwoLineOrder(BigDecimal("30000"), BigDecimal("20000"))
        val line1 = orderLineRepository.findByOrderId(orderId).first { it.skuId == skus.first }

        // PREPARING 상태 요청 거부
        shouldThrow<BusinessException> {
            returnClaimService.requestReturn(buyerId, orderId, listOf(line1.id), ReturnReason.CHANGED_MIND)
        }.errorCode shouldBe ErrorCode.RETURN_NOT_ALLOWED

        // SHIPPED(배송중)에서도 아직 반품 불가
        shippingService.ship(orderId, "CJ", "T1")
        shouldThrow<BusinessException> {
            returnClaimService.requestReturn(buyerId, orderId, listOf(line1.id), ReturnReason.CHANGED_MIND)
        }.errorCode shouldBe ErrorCode.RETURN_NOT_ALLOWED
    }

    @Test
    fun `rejected claim does not refund`() {
        val (buyerId, orderId, skus) = placeTwoLineOrder(BigDecimal("30000"), BigDecimal("20000"))
        deliver(orderId)
        val line1 = orderLineRepository.findByOrderId(orderId).first { it.skuId == skus.first }
        val claim = returnClaimService.requestReturn(buyerId, orderId, listOf(line1.id), ReturnReason.CHANGED_MIND)

        returnClaimService.rejectReturn(claim.id)

        returnClaimService.getDetail(claim.id).claim.status shouldBe ReturnClaimStatus.REJECTED
        orderLineRepository.findByOrderId(orderId).first { it.id == line1.id }.refunded shouldBe false
        stockService.getBySkuId(skus.first).quantity shouldBe 99 // 미복원
        orderService.getDetail(orderId).order.status shouldBe OrderStatus.PAID
        // 완료 처리된 것이 아니므로 승인 시도는 거부되지 않고 정상 흐름? 이미 REJECTED라 재처리 불가
        shouldThrow<BusinessException> { returnClaimService.approveReturn(claim.id) }
            .errorCode shouldBe ErrorCode.CLAIM_NOT_PENDING
    }

    @Test
    fun `guards - duplicate line, foreign line, empty, non-owner, non-pending approve`() {
        val (buyerId, orderId, skus) = placeTwoLineOrder(BigDecimal("30000"), BigDecimal("20000"))
        deliver(orderId)
        val line1 = orderLineRepository.findByOrderId(orderId).first { it.skuId == skus.first }
        returnClaimService.requestReturn(buyerId, orderId, listOf(line1.id), ReturnReason.OTHER)

        // 진행 중 클레임에 이미 포함된 라인 재요청
        shouldThrow<BusinessException> {
            returnClaimService.requestReturn(buyerId, orderId, listOf(line1.id), ReturnReason.OTHER)
        }.errorCode shouldBe ErrorCode.LINE_ALREADY_IN_CLAIM
        // 이 주문에 없는 라인
        shouldThrow<BusinessException> {
            returnClaimService.requestReturn(buyerId, orderId, listOf(999_999L), ReturnReason.OTHER)
        }.errorCode shouldBe ErrorCode.INVALID_CLAIM_LINES
        // 빈 요청
        shouldThrow<BusinessException> {
            returnClaimService.requestReturn(buyerId, orderId, emptyList(), ReturnReason.OTHER)
        }.errorCode shouldBe ErrorCode.INVALID_CLAIM_LINES
        // 타인 주문
        shouldThrow<BusinessException> {
            returnClaimService.requestReturn(fixtures.createMember().id, orderId, listOf(line1.id), ReturnReason.OTHER)
        }.errorCode shouldBe ErrorCode.ACCESS_DENIED
    }

    @Test
    fun `shipped order cannot be self-cancelled - must go through return claim`() {
        val (buyerId, orderId, _) = placeTwoLineOrder(BigDecimal("30000"), BigDecimal("20000"))

        // 발송 전에는 취소 가능해야 하므로, 여기서는 발송 후 취소가 막히는지만 검증한다.
        shippingService.ship(orderId, "CJ", "T1")
        shouldThrow<BusinessException> { orderService.cancelOrder(buyerId, orderId) }
            .errorCode shouldBe ErrorCode.ORDER_NOT_CANCELLABLE
        // 배송완료 후에도 셀프 취소 불가
        shippingService.deliver(orderId)
        shouldThrow<BusinessException> { orderService.cancelOrder(buyerId, orderId) }
            .errorCode shouldBe ErrorCode.ORDER_NOT_CANCELLABLE
        orderService.getDetail(orderId).order.status shouldBe OrderStatus.PAID // 변화 없음
    }

    @Test
    fun `unshipped order can still be cancelled`() {
        val (buyerId, orderId, _) = placeTwoLineOrder(BigDecimal("30000"), BigDecimal("20000"))
        orderService.cancelOrder(buyerId, orderId) // PREPARING → 허용
        orderService.getDetail(orderId).order.status shouldBe OrderStatus.CANCELLED
    }

    @Test
    fun `shipment state machine rejects illegal transitions`() {
        val (_, orderId, _) = placeTwoLineOrder(BigDecimal("10000"), BigDecimal("10000"))

        // 발송 전 배송완료 불가
        shouldThrow<BusinessException> { shippingService.deliver(orderId) }
            .errorCode shouldBe ErrorCode.INVALID_STATE_TRANSITION

        shippingService.ship(orderId, "CJ", "T1")
        // 중복 발송 불가
        shouldThrow<BusinessException> { shippingService.ship(orderId, "CJ", "T2") }
            .errorCode shouldBe ErrorCode.INVALID_STATE_TRANSITION
    }
}
