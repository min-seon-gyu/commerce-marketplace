package com.commerce.promotion.interfaces.dto

import com.commerce.promotion.domain.Coupon
import com.commerce.promotion.domain.DiscountType
import com.commerce.promotion.domain.Promotion
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import java.math.BigDecimal
import java.time.LocalDateTime

data class CreatePromotionRequest(
    @field:NotBlank val name: String,
    @field:NotNull val discountType: DiscountType,
    @field:NotNull val discountValue: BigDecimal,
    @field:NotNull val minSpend: BigDecimal,
    @field:NotNull @field:Min(1) val perMemberLimit: Int,
    @field:NotNull val budgetLimit: BigDecimal,
    @field:NotNull val startsAt: LocalDateTime,
    @field:NotNull val endsAt: LocalDateTime,
)

data class PromotionResponse(
    val id: Long,
    val name: String,
    val discountType: DiscountType,
    val discountValue: BigDecimal,
    val minSpend: BigDecimal,
    val perMemberLimit: Int,
    val budgetLimit: BigDecimal,
    val status: String,
    val startsAt: LocalDateTime,
    val endsAt: LocalDateTime,
) {
    companion object {
        fun from(p: Promotion) = PromotionResponse(
            id = p.id, name = p.name, discountType = p.discountType, discountValue = p.discountValue,
            minSpend = p.minSpend, perMemberLimit = p.perMemberLimit, budgetLimit = p.budgetLimit,
            status = p.status.name, startsAt = p.startsAt, endsAt = p.endsAt,
        )
    }
}

data class CouponResponse(
    val id: Long,
    val promotionId: Long,
    val memberId: Long,
    val status: String,
    val expiresAt: LocalDateTime,
) {
    companion object {
        fun from(c: Coupon) = CouponResponse(c.id, c.promotionId, c.memberId, c.status.name, c.expiresAt)
    }
}
