package com.commerce.promotion.infrastructure

import com.commerce.promotion.domain.Coupon
import org.springframework.data.jpa.repository.JpaRepository

interface CouponJpaRepository : JpaRepository<Coupon, Long> {
    fun findByMemberId(memberId: Long): List<Coupon>
}
