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

    /**
     * 사용 쿠폰을 되돌린다(REDEEMED→ISSUED). 주문 전체취소 시 단일 사용 쿠폰을 고객에게 반환하기 위한 것.
     * 만료 여부는 되돌린 뒤 재사용 시점에 [redeem]이 검사하므로 여기서는 상태만 복원한다.
     */
    fun restore() {
        if (status != CouponStatus.REDEEMED) throw BusinessException(ErrorCode.INVALID_STATE_TRANSITION)
        status = CouponStatus.ISSUED
    }

    fun expire() {
        if (status != CouponStatus.ISSUED) throw BusinessException(ErrorCode.INVALID_STATE_TRANSITION)
        status = CouponStatus.EXPIRED
    }

    fun isExpired(now: LocalDateTime = LocalDateTime.now()): Boolean = expiresAt.isBefore(now)
}
