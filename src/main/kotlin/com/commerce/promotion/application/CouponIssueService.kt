package com.commerce.promotion.application

import com.commerce.common.exception.BusinessException
import com.commerce.common.exception.ErrorCode
import com.commerce.promotion.domain.Coupon
import com.commerce.promotion.infrastructure.CouponJpaRepository
import com.commerce.promotion.infrastructure.PromotionJpaRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate

@Service
class CouponIssueService(
    private val promotionRepository: PromotionJpaRepository,
    private val couponRepository: CouponJpaRepository,
    private val transactionTemplate: TransactionTemplate,
) {

    fun issue(promotionId: Long, memberId: Long): Coupon =
        transactionTemplate.execute { _ ->
            val promotion = promotionRepository.findById(promotionId)
                .orElseThrow { BusinessException(ErrorCode.ENTITY_NOT_FOUND) }
            if (!promotion.isActive()) throw BusinessException(ErrorCode.PROMOTION_NOT_ACTIVE)
            couponRepository.save(
                Coupon(promotionId = promotionId, memberId = memberId, expiresAt = promotion.endsAt)
            )
        }!!

    fun findByMember(memberId: Long): List<Coupon> = couponRepository.findByMemberId(memberId)
}
