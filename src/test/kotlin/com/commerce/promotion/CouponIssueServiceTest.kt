package com.commerce.promotion

import com.commerce.common.exception.BusinessException
import com.commerce.common.exception.ErrorCode
import com.commerce.promotion.application.CouponIssueService
import com.commerce.promotion.application.PromotionService
import com.commerce.promotion.domain.CouponStatus
import com.commerce.promotion.domain.DiscountType
import com.commerce.promotion.interfaces.dto.CreatePromotionRequest
import com.commerce.support.IntegrationTestSupport
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.math.BigDecimal
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

class CouponIssueServiceTest : IntegrationTestSupport() {

    @Autowired lateinit var promotionService: PromotionService
    @Autowired lateinit var couponIssueService: CouponIssueService

    // Truncate to microseconds to match MySQL DATETIME(6) storage precision
    private fun now() = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS)

    private fun createPromotionRequest() = CreatePromotionRequest(
        name = "발급 테스트",
        discountType = DiscountType.FIXED,
        discountValue = BigDecimal("3000"),
        minSpend = BigDecimal.ZERO,
        perMemberLimit = 1,
        budgetLimit = BigDecimal("1000000"),
        startsAt = now().minusDays(1),
        endsAt = now().plusDays(7),
    )

    @Test
    fun `issue creates an ISSUED coupon owned by the member with promotion expiry`() {
        val promotion = promotionService.create(createPromotionRequest())
        val coupon = couponIssueService.issue(promotion.id, memberId = 42L)

        coupon.status shouldBe CouponStatus.ISSUED
        coupon.memberId shouldBe 42L
        coupon.promotionId shouldBe promotion.id
        coupon.expiresAt shouldBe promotion.endsAt
        couponIssueService.findByMember(42L).map { it.id } shouldBe listOf(coupon.id)
    }

    @Test
    fun `issue on an inactive promotion is rejected`() {
        val promotion = promotionService.create(
            createPromotionRequest().copy(
                startsAt = now().minusDays(10),
                endsAt = now().minusDays(1), // 이미 종료
            )
        )
        shouldThrow<BusinessException> { couponIssueService.issue(promotion.id, 42L) }
            .errorCode shouldBe ErrorCode.PROMOTION_NOT_ACTIVE
    }
}
