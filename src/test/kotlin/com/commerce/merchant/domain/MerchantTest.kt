package com.commerce.merchant.domain

import com.commerce.common.exception.BusinessException
import com.commerce.common.exception.ErrorCode
import com.commerce.member.domain.Member
import com.commerce.member.domain.MemberStatus
import com.commerce.region.domain.Region
import com.commerce.region.domain.RegionPolicy
import com.commerce.region.domain.SettlementPeriod
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import java.math.BigDecimal

class MerchantTest : DescribeSpec({
    fun createMerchant(status: MerchantStatus = MerchantStatus.PENDING_APPROVAL): Merchant {
        val region = Region("성남시", "SN", RegionPolicy(BigDecimal("0.10"), BigDecimal("500000"), BigDecimal("10000000000"), BigDecimal("0.60"), SettlementPeriod.MONTHLY))
        val owner = Member("owner@test.com", "사장님", "encoded", MemberStatus.ACTIVE)
        return Merchant("테스트가게", "123-45-67890", MerchantCategory.RESTAURANT, region, owner, status)
    }

    describe("Merchant state transitions") {
        it("PENDING_APPROVAL -> APPROVED") {
            val m = createMerchant(MerchantStatus.PENDING_APPROVAL)
            m.approve()
            m.status shouldBe MerchantStatus.APPROVED
        }

        it("PENDING_APPROVAL -> REJECTED") {
            val m = createMerchant(MerchantStatus.PENDING_APPROVAL)
            m.reject()
            m.status shouldBe MerchantStatus.REJECTED
        }

        it("APPROVED -> SUSPENDED") {
            val m = createMerchant(MerchantStatus.APPROVED)
            m.suspend()
            m.status shouldBe MerchantStatus.SUSPENDED
        }

        it("SUSPENDED -> APPROVED (unsuspend)") {
            val m = createMerchant(MerchantStatus.SUSPENDED)
            m.unsuspend()
            m.status shouldBe MerchantStatus.APPROVED
        }

        it("APPROVED -> TERMINATED") {
            val m = createMerchant(MerchantStatus.APPROVED)
            m.terminate()
            m.status shouldBe MerchantStatus.TERMINATED
        }

        it("SUSPENDED -> TERMINATED") {
            val m = createMerchant(MerchantStatus.SUSPENDED)
            m.terminate()
            m.status shouldBe MerchantStatus.TERMINATED
        }

        it("REJECTED -> APPROVED is invalid") {
            val m = createMerchant(MerchantStatus.REJECTED)
            val ex = shouldThrow<BusinessException> { m.approve() }
            ex.errorCode shouldBe ErrorCode.INVALID_STATE_TRANSITION
        }

        it("PENDING_APPROVAL -> TERMINATED is invalid") {
            val m = createMerchant(MerchantStatus.PENDING_APPROVAL)
            val ex = shouldThrow<BusinessException> { m.terminate() }
            ex.errorCode shouldBe ErrorCode.INVALID_STATE_TRANSITION
        }
    }
})
