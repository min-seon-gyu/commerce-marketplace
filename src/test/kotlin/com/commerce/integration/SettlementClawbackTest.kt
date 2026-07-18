package com.commerce.integration

import com.commerce.ledger.application.LedgerVerificationService
import com.commerce.order.application.OrderService
import com.commerce.order.infrastructure.OrderLineJpaRepository
import com.commerce.seller.application.SettlementService
import com.commerce.support.IntegrationTestSupport
import com.commerce.support.TestFixtures
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.math.BigDecimal
import java.time.LocalDate

/**
 * 누적 정산 모델의 clawback 검증 — 정산 확정/지급 이후 발생한 환불이 다음 정산에서 차감되어
 * 판매자 과지급이 방지되는지 확인한다. (기존 테스트는 판매자당 정산 1건이라 이 동작을 검증하지 못함.)
 */
class SettlementClawbackTest : IntegrationTestSupport() {

    @Autowired lateinit var fixtures: TestFixtures
    @Autowired lateinit var orderService: OrderService
    @Autowired lateinit var orderLineRepository: OrderLineJpaRepository
    @Autowired lateinit var settlementService: SettlementService
    @Autowired lateinit var verificationService: LedgerVerificationService

    private fun refundEntireOrder(buyerId: Long, orderId: Long) =
        orderService.refundLines(buyerId, orderId, orderLineRepository.findByOrderId(orderId).map { it.id })

    @Test
    fun `refund after a settled order is clawed back from the seller's next settlement`() {
        // 단일일 정산 구간을 쓰므로 DAILY 판매자 — 명시 기간은 정산주기 경계와 일치해야 한다.
        val seller = fixtures.createSeller(fixtures.createMember(), "DAILY")
        val buyer = fixtures.createMember()

        // P1: 주문 A 10,000 → 정산 확정(판매자 10,000 지급)
        val orderA = fixtures.sellerSale(buyer.id, seller.id, BigDecimal("10000"))
        val p1 = settlementService.calculate(seller.id, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 1))
        p1.totalAmount.compareTo(BigDecimal("10000")) shouldBe 0
        settlementService.confirm(p1.id)

        // 정산 확정 이후 주문 A 전액 환불(과지급 상태 발생)
        refundEntireOrder(buyer.id, orderA.id)

        // P2: 주문 C 15,000 → 다음 정산 = 15,000 − 이미 지급 10,000(환불분 clawback) = 5,000
        fixtures.sellerSale(buyer.id, seller.id, BigDecimal("15000"))
        val p2 = settlementService.calculate(seller.id, LocalDate.of(2026, 1, 2), LocalDate.of(2026, 1, 2))
        p2.totalAmount.compareTo(BigDecimal("5000")) shouldBe 0
        settlementService.confirm(p2.id)

        // 총 지급 10,000 + 5,000 = 15,000 = 총 미환불 매출(C). 원장 균형 유지.
        verificationService.verify().isBalanced.shouldBeTrue()
    }

    @Test
    fun `deficit carries when refunds exceed new sales - no settlement is created`() {
        val seller = fixtures.createSeller(fixtures.createMember(), "DAILY")
        val buyer = fixtures.createMember()

        // 주문 A 10,000 정산·지급
        val orderA = fixtures.sellerSale(buyer.id, seller.id, BigDecimal("10000"))
        val p1 = settlementService.calculate(seller.id, LocalDate.of(2026, 2, 1), LocalDate.of(2026, 2, 1))
        settlementService.confirm(p1.id)

        // 확정 후 전액 환불
        refundEntireOrder(buyer.id, orderA.id)

        // 신규 매출 3,000 (환불 10,000보다 작음) → 순정산 3,000 − 10,000 = −7,000 → 이월(배치는 정산 미생성)
        fixtures.sellerSale(buyer.id, seller.id, BigDecimal("3000"))
        val built = settlementService.buildSettlementForBatch(seller.id, LocalDate.of(2026, 2, 15))

        built.shouldBeNull()
    }
}
