package com.commerce.integration

import com.commerce.order.application.OrderService
import com.commerce.seller.application.SettlementService
import com.commerce.seller.domain.SettlementStatus
import com.commerce.support.IntegrationTestSupport
import com.commerce.support.TestFixtures
import com.commerce.transaction.domain.TransactionType
import com.commerce.transaction.infrastructure.TransactionJpaRepository
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.math.BigDecimal
import java.time.LocalDate

/**
 * 정산 확정 검증:
 * - 0원 정산(해당 기간 결제 없음)도 예외 없이 CONFIRMED로 전이(Transaction 양수 제약 회피)
 * - 0원 초과 정산은 SETTLEMENT 거래를 생성(V1 ENUM에 누락됐던 'SETTLEMENT' 값이 V5로 추가되어 INSERT 성공)
 */
class SettlementConfirmTest : IntegrationTestSupport() {

    @Autowired lateinit var fixtures: TestFixtures
    @Autowired lateinit var settlementService: SettlementService
    @Autowired lateinit var orderService: OrderService
    @Autowired lateinit var transactionRepository: TransactionJpaRepository

    private var regionId: Long = 0
    private var memberId: Long = 0
    private var sellerId: Long = 0

    @BeforeEach
    fun setup() {
        val region = fixtures.createRegion()
        val member = fixtures.createMember()
        val seller = fixtures.createSeller(region, fixtures.createMember())
        regionId = region.id
        memberId = member.id
        sellerId = seller.id
    }

    private fun monthRange(): Pair<LocalDate, LocalDate> {
        val today = LocalDate.now()
        return today.withDayOfMonth(1) to today.withDayOfMonth(today.lengthOfMonth())
    }

    @Test
    fun `confirm a zero-amount settlement succeeds without creating a transaction`() {
        val (start, end) = monthRange()
        val settlement = settlementService.calculate(sellerId, start, end)
        settlement.totalAmount.compareTo(BigDecimal.ZERO) shouldBe 0

        val confirmed = settlementService.confirm(settlement.id)

        confirmed.status shouldBe SettlementStatus.CONFIRMED
    }

    @Test
    fun `confirm a non-zero settlement creates a SETTLEMENT transaction`() {
        fixtures.sellerSale(memberId, sellerId, BigDecimal("10000")) // PAID 주문 → 판매자 매출 10000

        val (start, end) = monthRange()
        val settlement = settlementService.calculate(sellerId, start, end)
        settlement.totalAmount.compareTo(BigDecimal("10000")) shouldBe 0

        val confirmed = settlementService.confirm(settlement.id)

        confirmed.status shouldBe SettlementStatus.CONFIRMED
        transactionRepository.findAll()
            .any { it.type == TransactionType.SETTLEMENT && it.sellerId == sellerId } shouldBe true
    }

    @Test
    fun `confirm recomputes total excluding orders cancelled after calculate`() {
        val order = fixtures.sellerSale(memberId, sellerId, BigDecimal("10000"))

        val (start, end) = monthRange()
        val settlement = settlementService.calculate(sellerId, start, end)
        settlement.totalAmount.compareTo(BigDecimal("10000")) shouldBe 0 // calculate 시점 스냅샷

        // PENDING 창에서 주문 취소 → 확정 재계산에서 제외되어야 한다.
        orderService.cancelOrder(memberId, order.id)

        val confirmed = settlementService.confirm(settlement.id)

        // 확정 시 재계산으로 취소된 주문분이 제외되어 과지급되지 않는다.
        confirmed.totalAmount.compareTo(BigDecimal.ZERO) shouldBe 0
        confirmed.status shouldBe SettlementStatus.CONFIRMED
    }
}
