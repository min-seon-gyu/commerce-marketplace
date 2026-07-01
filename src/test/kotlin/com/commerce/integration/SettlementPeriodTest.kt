package com.commerce.integration

import com.commerce.seller.application.SettlementService
import com.commerce.support.IntegrationTestSupport
import com.commerce.support.TestFixtures
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.math.BigDecimal
import java.time.DayOfWeek
import java.time.LocalDate

/**
 * 정산 기간이 판매자 소속 지자체의 정산 주기(일/주/월)에서 KST 역월 기준으로 산출되는지 검증한다.
 * (기존에는 호출자가 넘긴 임의 날짜를 그대로 사용했음 — settlementPeriod 미소비)
 */
class SettlementPeriodTest : IntegrationTestSupport() {

    @Autowired lateinit var fixtures: TestFixtures
    @Autowired lateinit var settlementService: SettlementService

    private fun setupAndSell(settlementPeriod: String, amount: BigDecimal): Long {
        val region = fixtures.createRegion(settlementPeriod = settlementPeriod)
        val owner = fixtures.createMember()
        val seller = fixtures.createSeller(region, owner)
        val buyer = fixtures.createMember()
        fixtures.sellerSale(buyer.id, seller.id, amount) // PAID 주문 1건 → 판매자 매출 amount
        return seller.id
    }

    @Test
    fun `monthly settlement period spans first to last day of reference month`() {
        val sellerId = setupAndSell("MONTHLY", BigDecimal("10000"))
        val today = LocalDate.now()

        val settlement = settlementService.calculateForPeriod(sellerId, today)

        settlement.periodStart shouldBe today.withDayOfMonth(1)
        settlement.periodEnd shouldBe today.withDayOfMonth(today.lengthOfMonth())
        settlement.totalAmount.compareTo(BigDecimal("10000")) shouldBe 0
    }

    @Test
    fun `weekly settlement period spans monday to sunday of reference week`() {
        val sellerId = setupAndSell("WEEKLY", BigDecimal("7000"))
        val today = LocalDate.now()

        val settlement = settlementService.calculateForPeriod(sellerId, today)

        settlement.periodStart shouldBe today.with(DayOfWeek.MONDAY)
        settlement.periodEnd shouldBe today.with(DayOfWeek.SUNDAY)
        settlement.totalAmount.compareTo(BigDecimal("7000")) shouldBe 0
    }

    @Test
    fun `daily settlement period is the single reference day`() {
        val sellerId = setupAndSell("DAILY", BigDecimal("5000"))
        val today = LocalDate.now()

        val settlement = settlementService.calculateForPeriod(sellerId, today)

        settlement.periodStart shouldBe today
        settlement.periodEnd shouldBe today
        settlement.totalAmount.compareTo(BigDecimal("5000")) shouldBe 0
    }
}
