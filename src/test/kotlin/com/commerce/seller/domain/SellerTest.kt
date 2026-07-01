package com.commerce.seller.domain

import com.commerce.common.exception.BusinessException
import com.commerce.common.exception.ErrorCode
import com.commerce.member.domain.Member
import com.commerce.member.domain.MemberStatus
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import java.math.BigDecimal

class SellerTest : DescribeSpec({
    fun createSeller(status: SellerStatus = SellerStatus.PENDING_APPROVAL): Seller {
        val owner = Member("owner@test.com", "사장님", "encoded", MemberStatus.ACTIVE)
        return Seller("테스트가게", "123-45-67890", SellerCategory.RESTAURANT, SettlementPeriod.MONTHLY, owner, status)
    }

    describe("Seller state transitions") {
        it("PENDING_APPROVAL -> APPROVED") {
            val m = createSeller(SellerStatus.PENDING_APPROVAL)
            m.approve()
            m.status shouldBe SellerStatus.APPROVED
        }

        it("PENDING_APPROVAL -> REJECTED") {
            val m = createSeller(SellerStatus.PENDING_APPROVAL)
            m.reject()
            m.status shouldBe SellerStatus.REJECTED
        }

        it("APPROVED -> SUSPENDED") {
            val m = createSeller(SellerStatus.APPROVED)
            m.suspend()
            m.status shouldBe SellerStatus.SUSPENDED
        }

        it("SUSPENDED -> APPROVED (unsuspend)") {
            val m = createSeller(SellerStatus.SUSPENDED)
            m.unsuspend()
            m.status shouldBe SellerStatus.APPROVED
        }

        it("APPROVED -> TERMINATED") {
            val m = createSeller(SellerStatus.APPROVED)
            m.terminate()
            m.status shouldBe SellerStatus.TERMINATED
        }

        it("SUSPENDED -> TERMINATED") {
            val m = createSeller(SellerStatus.SUSPENDED)
            m.terminate()
            m.status shouldBe SellerStatus.TERMINATED
        }

        it("REJECTED -> APPROVED is invalid") {
            val m = createSeller(SellerStatus.REJECTED)
            val ex = shouldThrow<BusinessException> { m.approve() }
            ex.errorCode shouldBe ErrorCode.INVALID_STATE_TRANSITION
        }

        it("PENDING_APPROVAL -> TERMINATED is invalid") {
            val m = createSeller(SellerStatus.PENDING_APPROVAL)
            val ex = shouldThrow<BusinessException> { m.terminate() }
            ex.errorCode shouldBe ErrorCode.INVALID_STATE_TRANSITION
        }
    }
})
