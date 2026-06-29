package com.commerce.point.application

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.mockk
import java.math.BigDecimal

class PointEarnServiceTest : DescribeSpec({

    // 순수 계산 검증이라 리포지토리/원장은 모킹, 적립률은 0.01 고정 주입.
    val service = PointEarnService(
        pointAccountRepository = mockk(relaxed = true),
        pointTransactionRepository = mockk(relaxed = true),
        ledgerService = mockk(relaxed = true),
        meterRegistry = SimpleMeterRegistry(),
        earnRate = BigDecimal("0.01"),
    )

    describe("calculateEarn (1% , 1원 단위 HALF_UP)") {
        it("rounds exact and fractional amounts") {
            service.calculateEarn(BigDecimal("20000")).compareTo(BigDecimal("200")) shouldBe 0
            service.calculateEarn(BigDecimal("15000")).compareTo(BigDecimal("150")) shouldBe 0
            // 12350 * 0.01 = 123.50 -> HALF_UP -> 124
            service.calculateEarn(BigDecimal("12350")).compareTo(BigDecimal("124")) shouldBe 0
            // 12340 * 0.01 = 123.40 -> HALF_UP -> 123
            service.calculateEarn(BigDecimal("12340")).compareTo(BigDecimal("123")) shouldBe 0
        }

        it("returns 0 when the base amount is too small to earn 1 won") {
            // 49 * 0.01 = 0.49 -> 0
            service.calculateEarn(BigDecimal("49")).compareTo(BigDecimal.ZERO) shouldBe 0
        }
    }
})
