package com.commerce.promotion.domain

import com.commerce.common.domain.BaseEntity
import com.commerce.common.exception.BusinessException
import com.commerce.common.exception.ErrorCode
import jakarta.persistence.*
import java.time.LocalDateTime

@Entity
@Table(
    name = "coupons",
    indexes = [
        Index(name = "idx_coupon_member", columnList = "member_id, status"),
        Index(name = "idx_coupon_promotion", columnList = "promotion_id"),
    ]
)
class Coupon(
    @Column(nullable = false)
    val promotionId: Long,

    @Column(nullable = false)
    val memberId: Long,

    @Column(nullable = false)
    val expiresAt: LocalDateTime,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var status: CouponStatus = CouponStatus.ISSUED,
) : BaseEntity() {

    /** STRETCH 비동기 예약 흐름 전용. MUST 동기 흐름에서는 사용하지 않는다. */
    fun reserve() {
        if (status != CouponStatus.ISSUED) throw BusinessException(ErrorCode.COUPON_ALREADY_USED)
        status = CouponStatus.RESERVED
    }

    fun redeem(now: LocalDateTime = LocalDateTime.now()) {
        if (status != CouponStatus.ISSUED) throw BusinessException(ErrorCode.COUPON_ALREADY_USED)
        if (isExpired(now)) throw BusinessException(ErrorCode.COUPON_EXPIRED)
        status = CouponStatus.REDEEMED
    }

    fun cancel() {
        if (status != CouponStatus.REDEEMED) throw BusinessException(ErrorCode.INVALID_STATE_TRANSITION)
        status = CouponStatus.CANCELLED
    }

    fun expire() {
        if (status != CouponStatus.ISSUED) throw BusinessException(ErrorCode.INVALID_STATE_TRANSITION)
        status = CouponStatus.EXPIRED
    }

    fun isExpired(now: LocalDateTime = LocalDateTime.now()): Boolean = expiresAt.isBefore(now)
}
