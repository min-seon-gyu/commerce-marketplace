package com.commerce.member.domain

import com.commerce.common.domain.BaseEntity
import com.commerce.common.exception.BusinessException
import com.commerce.common.exception.ErrorCode
import jakarta.persistence.*

@Entity
@Table(name = "members")
class Member(
    @Column(nullable = false, unique = true)
    val email: String,

    @Column(nullable = false, length = 50)
    var name: String,

    @Column(nullable = false)
    var password: String,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 15)
    var status: MemberStatus = MemberStatus.PENDING,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var role: MemberRole = MemberRole.USER,
) : BaseEntity() {

    fun activate() {
        if (status != MemberStatus.PENDING)
            throw BusinessException(ErrorCode.INVALID_STATE_TRANSITION, "PENDING 상태에서만 활성화할 수 있습니다")
        status = MemberStatus.ACTIVE
    }

    fun suspend() {
        if (status != MemberStatus.ACTIVE)
            throw BusinessException(ErrorCode.INVALID_STATE_TRANSITION, "ACTIVE 상태에서만 정지할 수 있습니다")
        status = MemberStatus.SUSPENDED
    }

    fun unsuspend() {
        if (status != MemberStatus.SUSPENDED)
            throw BusinessException(ErrorCode.INVALID_STATE_TRANSITION, "SUSPENDED 상태에서만 정지 해제할 수 있습니다")
        status = MemberStatus.ACTIVE
    }

    fun withdraw() {
        if (status != MemberStatus.ACTIVE && status != MemberStatus.SUSPENDED)
            throw BusinessException(ErrorCode.INVALID_STATE_TRANSITION, "ACTIVE 또는 SUSPENDED 상태에서만 탈퇴할 수 있습니다")
        status = MemberStatus.WITHDRAWN
    }

    fun isActive(): Boolean = status == MemberStatus.ACTIVE
}
