package com.commerce.promotion.infrastructure

import com.commerce.promotion.domain.CouponRedemption
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import java.math.BigDecimal

interface CouponRedemptionJpaRepository : JpaRepository<CouponRedemption, Long> {

    fun findByTransactionId(transactionId: Long): CouponRedemption?

    fun countByMemberIdAndPromotionIdAndCancelledFalse(memberId: Long, promotionId: Long): Long

    @Query("""
        SELECT COALESCE(SUM(cr.discountAmount), 0) FROM CouponRedemption cr
        WHERE cr.promotionId = :promotionId AND cr.cancelled = false
    """)
    fun sumActiveDiscountByPromotion(promotionId: Long): BigDecimal
}
