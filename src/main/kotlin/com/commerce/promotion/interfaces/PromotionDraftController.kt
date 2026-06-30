package com.commerce.promotion.interfaces

import com.commerce.common.api.ApiResponse
import com.commerce.common.idempotency.Idempotent
import com.commerce.common.security.SecurityUtils
import com.commerce.promotion.application.LlmDraftCommand
import com.commerce.promotion.application.PromotionDraftService
import com.commerce.promotion.interfaces.dto.CreatePromotionDraftRequest
import com.commerce.promotion.interfaces.dto.PromotionDraftResponse
import jakarta.validation.Valid
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/promotions")
class PromotionDraftController(
    private val promotionDraftService: PromotionDraftService,
) {

    /**
     * AI 프로모션 초안 생성. 멱등(Idempotency-Key 헤더 권장 → 중복 과금 방지).
     * 호출자 신원은 Plan 1 의 SecurityUtils.currentMemberId()(인증 principal)에서 도출하며
     * 본문 memberId 는 신뢰하지 않는다. principal 부재 시 헬퍼가 ErrorCode.UNAUTHORIZED 를 던진다.
     */
    @Idempotent
    @PostMapping("/draft")
    fun createDraft(@Valid @RequestBody request: CreatePromotionDraftRequest): ApiResponse<PromotionDraftResponse> {
        val memberId = SecurityUtils.currentMemberId()
        val result = promotionDraftService.draft(
            LlmDraftCommand(prompt = request.prompt, context = request.context),
            requesterMemberId = memberId,
        )
        return ApiResponse.ok(PromotionDraftResponse.from(result))
    }
}
