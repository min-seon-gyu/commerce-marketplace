package com.commerce.promotion.infrastructure

import com.commerce.promotion.domain.Coupon
import com.commerce.promotion.domain.CouponStatus
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.math.BigDecimal

interface CouponJpaRepository : JpaRepository<Coupon, Long> {
    fun findByMemberId(memberId: Long): List<Coupon>

    /** 회원의 특정 프로모션 쿠폰 중 주어진 상태(예: REDEEMED) 개수 — 1인 사용 한도 검증용. */
    fun countByMemberIdAndPromotionIdAndStatus(memberId: Long, promotionId: Long, status: CouponStatus): Long

    /**
     * 프로모션의 실제 소비 예산(진실원천) — 취소되지 않은 쿠폰 주문의 할인액 합.
     * 전체취소(CANCELLED) 주문은 예산을 반환하므로 제외하고, 부분/전액 환불 주문은 예산 소진을 유지하므로 포함한다.
     * `orders`가 JPQL 예약어라 네이티브 쿼리로 작성한다. Redis 예산 카운터 재동기화의 기준이 된다.
     */
    @Query(
        value = """
            select coalesce(sum(o.discount_amount), 0)
            from orders o join coupons c on o.coupon_id = c.id
            where c.promotion_id = :promotionId and o.status <> 'CANCELLED'
        """,
        nativeQuery = true,
    )
    fun sumConsumedBudgetByPromotion(@Param("promotionId") promotionId: Long): BigDecimal
}
