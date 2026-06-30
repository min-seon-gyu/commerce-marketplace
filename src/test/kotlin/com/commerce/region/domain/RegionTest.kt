package com.commerce.region.domain

import com.commerce.common.exception.BusinessException
import com.commerce.common.exception.ErrorCode
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import java.math.BigDecimal

class RegionTest : DescribeSpec({
    describe("Region state transitions") {
        it("ACTIVE -> SUSPENDED is valid") {
            val region = createRegion(RegionStatus.ACTIVE)
            region.suspend()
            region.status shouldBe RegionStatus.SUSPENDED
        }

        it("SUSPENDED -> ACTIVE is valid") {
            val region = createRegion(RegionStatus.SUSPENDED)
            region.activate()
            region.status shouldBe RegionStatus.ACTIVE
        }

        it("SUSPENDED -> DEACTIVATED is valid") {
            val region = createRegion(RegionStatus.SUSPENDED)
            region.deactivate()
            region.status shouldBe RegionStatus.DEACTIVATED
        }

        it("ACTIVE -> DEACTIVATED is invalid") {
            val region = createRegion(RegionStatus.ACTIVE)
            val ex = shouldThrow<BusinessException> { region.deactivate() }
            ex.errorCode shouldBe ErrorCode.INVALID_STATE_TRANSITION
        }

        it("DEACTIVATED -> ACTIVE is invalid") {
            val region = createRegion(RegionStatus.DEACTIVATED)
            val ex = shouldThrow<BusinessException> { region.activate() }
            ex.errorCode shouldBe ErrorCode.INVALID_STATE_TRANSITION
        }
    }

    describe("Region policy update") {
        it("should allow policy update when ACTIVE") {
            val region = createRegion(RegionStatus.ACTIVE)
            val newPolicy = region.policy.copy(discountRate = BigDecimal("0.15"))
            region.updatePolicy(newPolicy)
            region.policy.discountRate shouldBe BigDecimal("0.15")
        }

        it("should reject policy update when SUSPENDED") {
            val region = createRegion(RegionStatus.SUSPENDED)
            val newPolicy = region.policy.copy(discountRate = BigDecimal("0.15"))
            val ex = shouldThrow<BusinessException> { region.updatePolicy(newPolicy) }
            ex.errorCode shouldBe ErrorCode.REGION_NOT_ACTIVE
        }
    }
})

fun createRegion(status: RegionStatus = RegionStatus.ACTIVE): Region {
    return Region(
        name = "성남시",
        regionCode = "SN",
        policy = RegionPolicy(
            discountRate = BigDecimal("0.10"),
            purchaseLimitPerPerson = BigDecimal("500000"),
            monthlyIssuanceLimit = BigDecimal("10000000000"),
            refundThresholdRatio = BigDecimal("0.60"),
            settlementPeriod = SettlementPeriod.MONTHLY
        ),
        status = status
    )
}
