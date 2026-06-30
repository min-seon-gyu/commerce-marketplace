package com.commerce.member.domain

import com.commerce.common.exception.BusinessException
import com.commerce.common.exception.ErrorCode
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe

class MemberTest : DescribeSpec({
    fun createMember(status: MemberStatus = MemberStatus.PENDING) = Member(
        email = "test@test.com", name = "홍길동", password = "encoded", status = status
    )

    describe("Member state transitions") {
        it("PENDING -> ACTIVE") {
            val member = createMember(MemberStatus.PENDING)
            member.activate()
            member.status shouldBe MemberStatus.ACTIVE
        }

        it("ACTIVE -> SUSPENDED") {
            val member = createMember(MemberStatus.ACTIVE)
            member.suspend()
            member.status shouldBe MemberStatus.SUSPENDED
        }

        it("SUSPENDED -> ACTIVE") {
            val member = createMember(MemberStatus.SUSPENDED)
            member.unsuspend()
            member.status shouldBe MemberStatus.ACTIVE
        }

        it("ACTIVE -> WITHDRAWN") {
            val member = createMember(MemberStatus.ACTIVE)
            member.withdraw()
            member.status shouldBe MemberStatus.WITHDRAWN
        }

        it("SUSPENDED -> WITHDRAWN") {
            val member = createMember(MemberStatus.SUSPENDED)
            member.withdraw()
            member.status shouldBe MemberStatus.WITHDRAWN
        }

        it("PENDING -> SUSPENDED is invalid") {
            val member = createMember(MemberStatus.PENDING)
            val ex = shouldThrow<BusinessException> { member.suspend() }
            ex.errorCode shouldBe ErrorCode.INVALID_STATE_TRANSITION
        }

        it("WITHDRAWN -> ACTIVE is invalid") {
            val member = createMember(MemberStatus.WITHDRAWN)
            val ex = shouldThrow<BusinessException> { member.activate() }
            ex.errorCode shouldBe ErrorCode.INVALID_STATE_TRANSITION
        }
    }
})
