package com.commerce.integration

import com.commerce.common.exception.BusinessException
import com.commerce.common.exception.ErrorCode
import com.commerce.seller.application.SettlementService
import com.commerce.support.IntegrationTestSupport
import com.commerce.support.TestFixtures
import io.kotest.assertions.throwables.shouldThrow
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
        val owner = fixtures.createMember()
        val seller = fixtures.createSeller(owner, settlementPeriod)
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

    @Test
    fun `explicit period not aligned to the seller's cycle is rejected`() {
        val sellerId = setupAndSell("MONTHLY", BigDecimal("10000"))
        val today = LocalDate.now()

        // 월간 판매자에게 월 중간까지 자른 임의 구간(1일~12일) — periodEnd를 과거로 만들어
        // 주기 종료 확정 게이트를 우회하는 경로가 막혀야 한다.
        val ex = shouldThrow<BusinessException> {
            settlementService.calculate(sellerId, today.withDayOfMonth(1), today.withDayOfMonth(12))
        }

        ex.errorCode shouldBe ErrorCode.SETTLEMENT_PERIOD_MISMATCH
    }

    @Test
    fun `batch targets the most recently ended period, not the in-progress one`() {
        val sellerId = setupAndSell("MONTHLY", BigDecimal("10000"))

        // 3/13 기준 실행 — 3월 주기는 진행 중이므로 직전 주기(2월)를 대상으로 만든다.
        val midMonth = settlementService.buildSettlementForBatch(sellerId, LocalDate.of(2026, 3, 13))!!
        midMonth.periodStart shouldBe LocalDate.of(2026, 2, 1)
        midMonth.periodEnd shouldBe LocalDate.of(2026, 2, 28)

        // ref가 주기 마지막 날(3/31)이면 그 주기가 대상 — 4/1 03:00 실행(ref=전일)이 3월 정산을 만든다.
        val atBoundary = settlementService.buildSettlementForBatch(sellerId, LocalDate.of(2026, 3, 31))!!
        atBoundary.periodStart shouldBe LocalDate.of(2026, 3, 1)
        atBoundary.periodEnd shouldBe LocalDate.of(2026, 3, 31)
    }

    @Test
    fun `weekly batch targets the previous week until the week ends`() {
        val sellerId = setupAndSell("WEEKLY", BigDecimal("7000"))

        // 수요일(2026-03-11) 기준 실행 — 이번 주(3/9 월~3/15 일)는 진행 중이므로 지난주(3/2~3/8)를 대상으로 만든다.
        val midWeek = settlementService.buildSettlementForBatch(sellerId, LocalDate.of(2026, 3, 11))!!
        midWeek.periodStart shouldBe LocalDate.of(2026, 3, 2)
        midWeek.periodEnd shouldBe LocalDate.of(2026, 3, 8)

        // ref가 일요일(3/15)이면 그 주가 대상 — 월요일 03:00 실행(ref=전일)이 직전 주 정산을 만든다.
        val atBoundary = settlementService.buildSettlementForBatch(sellerId, LocalDate.of(2026, 3, 15))!!
        atBoundary.periodStart shouldBe LocalDate.of(2026, 3, 9)
        atBoundary.periodEnd shouldBe LocalDate.of(2026, 3, 15)
    }
}
