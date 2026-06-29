package com.commerce.region.domain

import com.commerce.common.domain.BaseEntity
import com.commerce.common.exception.BusinessException
import com.commerce.common.exception.ErrorCode
import jakarta.persistence.*

@Entity
@Table(name = "regions")
class Region(
    @Column(nullable = false, length = 50)
    val name: String,

    @Column(nullable = false, unique = true, length = 2)
    val regionCode: String,

    @Embedded
    var policy: RegionPolicy,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 15)
    var status: RegionStatus = RegionStatus.ACTIVE
) : BaseEntity() {

    fun suspend() {
        if (status != RegionStatus.ACTIVE)
            throw BusinessException(ErrorCode.INVALID_STATE_TRANSITION, "ACTIVE 상태에서만 정지할 수 있습니다")
        status = RegionStatus.SUSPENDED
    }

    fun activate() {
        if (status != RegionStatus.SUSPENDED)
            throw BusinessException(ErrorCode.INVALID_STATE_TRANSITION, "SUSPENDED 상태에서만 활성화할 수 있습니다")
        status = RegionStatus.ACTIVE
    }

    fun deactivate() {
        if (status != RegionStatus.SUSPENDED)
            throw BusinessException(ErrorCode.INVALID_STATE_TRANSITION, "SUSPENDED 상태에서만 종료할 수 있습니다")
        status = RegionStatus.DEACTIVATED
    }

    fun updatePolicy(newPolicy: RegionPolicy) {
        if (status != RegionStatus.ACTIVE)
            throw BusinessException(ErrorCode.REGION_NOT_ACTIVE)
        policy = newPolicy
    }
}
