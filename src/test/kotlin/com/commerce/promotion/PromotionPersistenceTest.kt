package com.commerce.promotion

import com.commerce.promotion.domain.Coupon
import com.commerce.promotion.domain.CouponRedemption
import com.commerce.promotion.domain.CouponStatus
import com.commerce.promotion.domain.DiscountType
import com.commerce.promotion.domain.Promotion
import com.commerce.promotion.domain.PromotionStatus
import com.commerce.promotion.infrastructure.CouponJpaRepository
import com.commerce.promotion.infrastructure.CouponRedemptionJpaRepository
import com.commerce.promotion.infrastructure.PromotionJpaRepository
import com.commerce.support.IntegrationTestSupport
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.math.BigDecimal
import java.time.LocalDateTime

class PromotionPersistenceTest : IntegrationTestSupport() {

    @Autowired lateinit var promotionRepository: PromotionJpaRepository
    @Autowired lateinit var couponRepository: CouponJpaRepository
    @Autowired lateinit var couponRedemptionRepository: CouponRedemptionJpaRepository

    @Test
    fun `promotion, coupon, redemption round-trip through validated schema`() {
        val promotion = promotionRepository.save(
            Promotion(
                name = "왕복 테스트",
                discountType = DiscountType.FIXED,
                discountValue = BigDecimal("3000"),
                minSpend = BigDecimal.ZERO,
                perMemberLimit = 1,
                budgetLimit = BigDecimal("1000000"),
                startsAt = LocalDateTime.now().minusDays(1),
                endsAt = LocalDateTime.now().plusDays(1),
                status = PromotionStatus.ACTIVE,
            )
        )
        val coupon = couponRepository.save(
            Coupon(promotionId = promotion.id, memberId = 10L, expiresAt = promotion.endsAt)
        )
        couponRedemptionRepository.save(
            CouponRedemption(
                couponId = coupon.id, promotionId = promotion.id, memberId = 10L,
                voucherId = 1L, transactionId = 1L,
                orderTotal = BigDecimal("10000"), discountAmount = BigDecimal("3000"),
                voucherCharged = BigDecimal("7000"),
            )
        )

        couponRepository.findById(coupon.id).get().status shouldBe CouponStatus.ISSUED
        couponRedemptionRepository.sumActiveDiscountByPromotion(promotion.id)
            .compareTo(BigDecimal("3000")) shouldBe 0
        couponRedemptionRepository.countByMemberIdAndPromotionIdAndCancelledFalse(10L, promotion.id) shouldBe 1L
    }
}
