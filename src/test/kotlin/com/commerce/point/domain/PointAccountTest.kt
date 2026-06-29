package com.commerce.point.domain

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import java.math.BigDecimal

class PointAccountTest : DescribeSpec({

    describe("earn") {
        it("adds the amount to the cached balance") {
            val account = PointAccount(memberId = 1L)
            account.earn(BigDecimal("200"))
            account.balance.compareTo(BigDecimal("200")) shouldBe 0
            account.earn(BigDecimal("150"))
            account.balance.compareTo(BigDecimal("350")) shouldBe 0
        }

        it("rejects non-positive amounts") {
            val account = PointAccount(memberId = 1L)
            shouldThrow<IllegalArgumentException> { account.earn(BigDecimal.ZERO) }
            shouldThrow<IllegalArgumentException> { account.earn(BigDecimal("-1")) }
        }
    }

    describe("construction") {
        it("rejects a negative initial balance") {
            shouldThrow<IllegalArgumentException> {
                PointAccount(memberId = 1L, balance = BigDecimal("-1"))
            }
        }
    }
})
