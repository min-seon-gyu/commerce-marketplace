package com.commerce.merchant.domain

import com.commerce.common.domain.BaseEntity
import com.commerce.common.exception.BusinessException
import com.commerce.common.exception.ErrorCode
import com.commerce.member.domain.Member
import com.commerce.region.domain.Region
import jakarta.persistence.*

@Entity
@Table(name = "merchants")
class Merchant(
    @Column(nullable = false, length = 100)
    val name: String,

    @Column(nullable = false, length = 20)
    val businessNumber: String,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    val category: MerchantCategory,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "region_id", nullable = false)
    val region: Region,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "owner_id", nullable = false)
    val owner: Member,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var status: MerchantStatus = MerchantStatus.PENDING_APPROVAL,
) : BaseEntity() {

    fun approve() {
        if (status != MerchantStatus.PENDING_APPROVAL)
            throw BusinessException(ErrorCode.INVALID_STATE_TRANSITION, "PENDING_APPROVAL 상태에서만 승인할 수 있습니다")
        status = MerchantStatus.APPROVED
    }

    fun reject() {
        if (status != MerchantStatus.PENDING_APPROVAL)
            throw BusinessException(ErrorCode.INVALID_STATE_TRANSITION, "PENDING_APPROVAL 상태에서만 거절할 수 있습니다")
        status = MerchantStatus.REJECTED
    }

    fun suspend() {
        if (status != MerchantStatus.APPROVED)
            throw BusinessException(ErrorCode.INVALID_STATE_TRANSITION, "APPROVED 상태에서만 정지할 수 있습니다")
        status = MerchantStatus.SUSPENDED
    }

    fun unsuspend() {
        if (status != MerchantStatus.SUSPENDED)
            throw BusinessException(ErrorCode.INVALID_STATE_TRANSITION, "SUSPENDED 상태에서만 정지 해제할 수 있습니다")
        status = MerchantStatus.APPROVED
    }

    fun terminate() {
        if (status != MerchantStatus.APPROVED && status != MerchantStatus.SUSPENDED)
            throw BusinessException(ErrorCode.INVALID_STATE_TRANSITION, "APPROVED 또는 SUSPENDED 상태에서만 해지할 수 있습니다")
        status = MerchantStatus.TERMINATED
    }

    fun isApproved(): Boolean = status == MerchantStatus.APPROVED
}
