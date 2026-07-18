package com.commerce.seller.domain

import com.commerce.common.exception.BusinessException
import com.commerce.common.exception.ErrorCode
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate

class SettlementTest {

    // 정산 기간 1/1~1/31 — 확정은 주기 종료 다음 날(2/1)부터 허용된다.
    private val afterPeriod = LocalDate.of(2026, 2, 1)

    private fun newSettlement() = Settlement(
        sellerId = 1L,
        periodStart = LocalDate.of(2026, 1, 1),
        periodEnd = LocalDate.of(2026, 1, 31),
        totalAmount = BigDecimal("10000"),
    )

    @Test
    fun `confirmed settlement can be marked paid`() {
        val settlement = newSettlement()
        settlement.confirm(afterPeriod)

        settlement.pay()

        settlement.status shouldBe SettlementStatus.PAID
    }

    @Test
    fun `pending settlement cannot be paid without confirmation`() {
        val settlement = newSettlement()

        shouldThrow<BusinessException> { settlement.pay() }
    }

    @Test
    fun `paid settlement cannot be paid again`() {
        val settlement = newSettlement()
        settlement.confirm(afterPeriod)
        settlement.pay()

        shouldThrow<BusinessException> { settlement.pay() }
    }

    @Test
    fun `disputed then confirmed settlement can be paid`() {
        val settlement = newSettlement()
        settlement.dispute("금액 불일치")
        settlement.confirm(afterPeriod)

        settlement.pay()

        settlement.status shouldBe SettlementStatus.PAID
    }

    @Test
    fun `cannot confirm before the settlement period ends`() {
        val settlement = newSettlement()

        // 주기 중(1/13) 확정 시도 — 월간 정산을 달이 끝나기 전에 확정할 수 없다.
        val ex = shouldThrow<BusinessException> { settlement.confirm(LocalDate.of(2026, 1, 13)) }

        ex.errorCode shouldBe ErrorCode.SETTLEMENT_PERIOD_NOT_ENDED
        settlement.status shouldBe SettlementStatus.PENDING
    }

    @Test
    fun `cannot confirm on the period end date itself`() {
        val settlement = newSettlement()

        // periodEnd 당일(1/31)도 아직 주기가 끝나지 않았다 — 다음 날부터 허용.
        val ex = shouldThrow<BusinessException> { settlement.confirm(LocalDate.of(2026, 1, 31)) }

        ex.errorCode shouldBe ErrorCode.SETTLEMENT_PERIOD_NOT_ENDED
    }
}
