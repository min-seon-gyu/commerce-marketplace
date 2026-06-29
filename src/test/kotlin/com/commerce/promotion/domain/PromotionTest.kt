package com.commerce.promotion.domain

import com.commerce.common.exception.BusinessException
import com.commerce.common.exception.ErrorCode
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDateTime

class PromotionTest {

    private fun promotion(
        type: DiscountType = DiscountType.FIXED,
        value: BigDecimal = BigDecimal("3000"),
        minSpend: BigDecimal = BigDecimal.ZERO,
        perMemberLimit: Int = 1,
        budgetLimit: BigDecimal = BigDecimal("1000000"),
        status: PromotionStatus = PromotionStatus.ACTIVE,
        startsAt: LocalDateTime = LocalDateTime.now().minusDays(1),
        endsAt: LocalDateTime = LocalDateTime.now().plusDays(1),
    ) = Promotion(
        name = "여름 할인",
        discountType = type,
        discountValue = value,
        minSpend = minSpend,
        perMemberLimit = perMemberLimit,
        budgetLimit = budgetLimit,
        startsAt = startsAt,
        endsAt = endsAt,
        status = status,
    )

    @Test
    fun `fixed discount returns the fixed value`() {
        promotion(DiscountType.FIXED, BigDecimal("3000"))
            .calculateDiscount(BigDecimal("10000")).compareTo(BigDecimal("3000")) shouldBe 0
    }

    @Test
    fun `percentage discount floors to whole won (no over-discount)`() {
        // 10% of 10,999 = 1,099.9 -> DOWN -> 1,099
        promotion(DiscountType.PERCENTAGE, BigDecimal("10"))
            .calculateDiscount(BigDecimal("10999")).compareTo(BigDecimal("1099")) shouldBe 0
    }

    @Test
    fun `calculateDiscount may exceed order total (clamp is applied by orchestrator)`() {
        // 정액 3,000 > 주문 2,000 — 엔티티는 raw 값을 반환하고, 클램프는 결합 오케스트레이터가 수행한다.
        promotion(DiscountType.FIXED, BigDecimal("3000"))
            .calculateDiscount(BigDecimal("2000")).compareTo(BigDecimal("3000")) shouldBe 0
    }

    @Test
    fun `zero discount value is rejected with INVALID_DISCOUNT`() {
        shouldThrow<BusinessException> { promotion(value = BigDecimal.ZERO) }
            .errorCode shouldBe ErrorCode.INVALID_DISCOUNT
    }

    @Test
    fun `negative discount value is rejected with INVALID_DISCOUNT`() {
        shouldThrow<BusinessException> { promotion(value = BigDecimal("-1")) }
            .errorCode shouldBe ErrorCode.INVALID_DISCOUNT
    }

    @Test
    fun `isActive is false when status is not ACTIVE`() {
        promotion(status = PromotionStatus.PAUSED).isActive() shouldBe false
    }

    @Test
    fun `isActive is false outside the validity window`() {
        val now = LocalDateTime.now()
        promotion(startsAt = now.plusDays(1), endsAt = now.plusDays(2)).isActive(now) shouldBe false
        promotion(startsAt = now.minusDays(2), endsAt = now.minusDays(1)).isActive(now) shouldBe false
    }

    @Test
    fun `activate transitions DRAFT to ACTIVE`() {
        val p = promotion(status = PromotionStatus.DRAFT)
        p.activate()
        p.status shouldBe PromotionStatus.ACTIVE
    }
}
