package com.commerce.promotion.interfaces.dto

import com.commerce.promotion.application.PromotionDraftResult
import com.commerce.promotion.domain.PromotionDraft
import com.commerce.promotion.domain.ValidationReport
import jakarta.validation.constraints.NotBlank

/** AI 초안 요청. memberId 는 본문이 아니라 인증 principal 에서 도출(미신뢰 정책). */
data class CreatePromotionDraftRequest(
    @field:NotBlank(message = "프롬프트는 비어 있을 수 없습니다")
    val prompt: String,
    val context: String? = null,
)

/** AI 초안 응답: 구조화 초안 + 결정적 검증 리포트(확정 전 사람이 검토). */
data class PromotionDraftResponse(
    val draft: PromotionDraft,
    val validation: ValidationReport,
) {
    companion object {
        fun from(result: PromotionDraftResult): PromotionDraftResponse =
            PromotionDraftResponse(result.draft, result.validation)
    }
}
