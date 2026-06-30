package com.commerce.promotion.domain

import com.commerce.common.domain.BaseEntity
import jakarta.persistence.*
import java.math.BigDecimal

@Entity
@Table(
    name = "coupon_redemptions",
    indexes = [
        Index(name = "idx_couponredemption_tx", columnList = "transaction_id"),
        Index(name = "idx_couponredemption_member_promo", columnList = "member_id, promotion_id, cancelled"),
    ]
)
class CouponRedemption(
    @Column(nullable = false) val couponId: Long,
    @Column(nullable = false) val promotionId: Long,
    @Column(nullable = false) val memberId: Long,
    @Column(nullable = false) val voucherId: Long,
    @Column(nullable = false) val transactionId: Long,
    @Column(nullable = false) val orderTotal: BigDecimal,     // T (gross)
    @Column(nullable = false) val discountAmount: BigDecimal, // D
    @Column(nullable = false) val voucherCharged: BigDecimal, // T - D
    @Column(nullable = false) var cancelled: Boolean = false,
) : BaseEntity() {
    fun markCancelled() { cancelled = true }
}
