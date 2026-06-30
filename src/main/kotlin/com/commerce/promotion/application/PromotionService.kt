package com.commerce.promotion.application

import com.commerce.common.exception.BusinessException
import com.commerce.common.exception.ErrorCode
import com.commerce.promotion.domain.Promotion
import com.commerce.promotion.domain.PromotionStatus
import com.commerce.promotion.infrastructure.PromotionJpaRepository
import com.commerce.promotion.interfaces.dto.CreatePromotionRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
@Transactional(readOnly = true)
class PromotionService(
    private val promotionRepository: PromotionJpaRepository,
) {

    @Transactional
    fun create(request: CreatePromotionRequest): Promotion =
        promotionRepository.save(
            Promotion(
                name = request.name,
                discountType = request.discountType,
                discountValue = request.discountValue,
                minSpend = request.minSpend,
                perMemberLimit = request.perMemberLimit,
                budgetLimit = request.budgetLimit,
                startsAt = request.startsAt,
                endsAt = request.endsAt,
                status = PromotionStatus.ACTIVE,
            )
        )

    fun getById(id: Long): Promotion =
        promotionRepository.findById(id)
            .orElseThrow { BusinessException(ErrorCode.ENTITY_NOT_FOUND) }
}
