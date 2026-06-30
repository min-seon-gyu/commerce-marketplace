package com.commerce.promotion.application

import com.commerce.promotion.domain.PromotionDraft
import com.commerce.promotion.domain.ValidationReport
import com.commerce.promotion.infrastructure.ai.LlmClient
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

data class PromotionDraftResult(
    val draft: PromotionDraft,
    val validation: ValidationReport,
)

/**
 * AI 초안 오케스트레이터: LLM 으로 구조화 초안을 받아 결정적 가드레일로 검증한다.
 * AI 는 DB 에 쓰지 않으며, 검증 통과 여부와 무관하게 초안+리포트를 반환한다(사람이 검토 후 확정).
 */
@Service
class PromotionDraftService(
    private val llmClient: LlmClient,
    private val validator: PromotionDraftValidator,
    private val meterRegistry: MeterRegistry,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun draft(command: LlmDraftCommand, requesterMemberId: Long): PromotionDraftResult {
        val draft = llmClient.generateDraft(command)
        val validation = validator.validate(draft)
        val result = if (validation.valid) "valid" else "rejected"
        meterRegistry.counter("ai.promotion.draft.count", "result", result).increment()
        log.info("AI 초안 생성: requester={}, target={}, valid={}", requesterMemberId, draft.target, validation.valid)
        return PromotionDraftResult(draft, validation)
    }
}
