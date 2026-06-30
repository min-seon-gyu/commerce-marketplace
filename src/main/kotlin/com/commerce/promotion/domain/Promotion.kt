package com.commerce.promotion.domain

import com.commerce.common.domain.BaseEntity
import com.commerce.common.exception.BusinessException
import com.commerce.common.exception.ErrorCode
import jakarta.persistence.*
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDateTime

@Entity
@Table(
    name = "promotions",
    indexes = [
        Index(name = "idx_promotion_status", columnList = "status,starts_at,ends_at"),
    ]
)
class Promotion(
    @Column(nullable = false, length = 100)
    val name: String,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    val discountType: DiscountType,

    @Column(nullable = false)
    val discountValue: BigDecimal,

    @Column(nullable = false)
    val minSpend: BigDecimal,

    @Column(nullable = false)
    val perMemberLimit: Int,

    @Column(nullable = false)
    val budgetLimit: BigDecimal,

    @Column(nullable = false)
    val startsAt: LocalDateTime,

    @Column(nullable = false)
    val endsAt: LocalDateTime,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var status: PromotionStatus = PromotionStatus.DRAFT,

    @Column(nullable = false)
    val stackable: Boolean = false, // MUST=false 고정 (스택은 STRETCH)
) : BaseEntity() {

    init {
        // 0/음수 할인 거부 (스펙 §4.1)
        if (discountValue <= BigDecimal.ZERO) throw BusinessException(ErrorCode.INVALID_DISCOUNT)
        require(budgetLimit > BigDecimal.ZERO) { "예산 한도는 0보다 커야 합니다" }
        require(minSpend >= BigDecimal.ZERO) { "최소 결제금액은 0 이상이어야 합니다" }
        require(perMemberLimit >= 1) { "회원당 사용 한도는 1 이상이어야 합니다" }
        require(endsAt.isAfter(startsAt)) { "종료일은 시작일 이후여야 합니다" }
    }

    /**
     * 주문총액에 대한 raw 할인액(클램프 전).
     * - FIXED: 정액
     * - PERCENTAGE: 주문총액 * 율 / 100, 0원 단위 내림(RoundingMode.DOWN, 과할인 방지)
     * 주문총액 초과(정액>주문) 가능 — 클램프 min(D, T)는 결합 오케스트레이터가 수행한다.
     */
    fun calculateDiscount(orderTotal: BigDecimal): BigDecimal = when (discountType) {
        DiscountType.FIXED -> discountValue
        DiscountType.PERCENTAGE ->
            orderTotal.multiply(discountValue).divide(BigDecimal(100), 0, RoundingMode.DOWN)
    }

    fun isActive(now: LocalDateTime = LocalDateTime.now()): Boolean =
        status == PromotionStatus.ACTIVE && !now.isBefore(startsAt) && now.isBefore(endsAt)

    fun activate() {
        if (status != PromotionStatus.DRAFT)
            throw BusinessException(ErrorCode.INVALID_STATE_TRANSITION)
        status = PromotionStatus.ACTIVE
    }
}
