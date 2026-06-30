package com.commerce.promotion.infrastructure.ai

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.commerce.common.exception.BusinessException
import com.commerce.common.exception.ErrorCode
import com.commerce.promotion.application.LlmDraftCommand
import com.commerce.promotion.domain.DraftDiscountType
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Test
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.web.client.ExpectedCount
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.header
import org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath
import org.springframework.test.web.client.match.MockRestRequestMatchers.method
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withBadRequest
import org.springframework.test.web.client.response.MockRestResponseCreators.withServerError
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.web.client.RestClient
import java.math.BigDecimal

class ClaudeLlmClientTest {

    private val mapper = ObjectMapper().registerKotlinModule().registerModule(JavaTimeModule())
    private val props = AiPromotionProperties(
        enabled = true,
        apiKey = "test-key-123",
        baseUrl = "https://api.anthropic.com",
        model = "claude-haiku-4-5",
        anthropicVersion = "2023-06-01",
        maxTokens = 1024,
        maxRetries = 2,
        backoffBaseMs = 0, // 테스트 가속
        circuitFailureThreshold = 3,
        circuitOpenMs = 10_000,
    )

    private fun okBody(): String = """
        {"id":"m","type":"message","role":"assistant","model":"claude-haiku-4-5",
         "stop_reason":"end_turn",
         "content":[{"type":"text","text":"{\"name\":\"성남시 7월 할인\",\"discountType\":\"PERCENTAGE\",\"discountValue\":10,\"target\":\"SN\",\"budgetCap\":50000000,\"minSpend\":10000,\"validFrom\":\"2026-07-01\",\"validUntil\":\"2026-07-31\",\"stackable\":false}"}],
         "usage":{"input_tokens":800,"output_tokens":120}}
    """.trimIndent()

    private fun newClient(
        builder: RestClient.Builder,
        cb: SimpleCircuitBreaker = SimpleCircuitBreaker(props.circuitFailureThreshold, props.circuitOpenMs),
    ) = ClaudeLlmClient(props, builder.build(), ClaudeResponseParser(mapper), mapper, SimpleMeterRegistry(), cb)

    @Test
    fun `요청 형태 헤더와 본문(model, max_tokens, output_config) 을 올바르게 보낸다`() {
        val builder = RestClient.builder().baseUrl(props.baseUrl)
        val server = MockRestServiceServer.bindTo(builder).build()
        server.expect(requestTo("https://api.anthropic.com/v1/messages"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(header("x-api-key", "test-key-123"))
            .andExpect(header("anthropic-version", "2023-06-01"))
            .andExpect(header("Content-Type", "application/json"))
            .andExpect(jsonPath("$.model").value("claude-haiku-4-5"))
            .andExpect(jsonPath("$.max_tokens").value(1024))
            .andExpect(jsonPath("$.output_config.format.type").value("json_schema"))
            .andExpect(jsonPath("$.messages[0].role").value("user"))
            .andRespond(withSuccess(okBody(), MediaType.APPLICATION_JSON))

        val draft = newClient(builder).generateDraft(LlmDraftCommand("성남시 10% 할인 7월", null))

        draft.target shouldBe "SN"
        draft.discountType shouldBe DraftDiscountType.PERCENTAGE
        draft.discountValue shouldBe BigDecimal("10")
        server.verify()
    }

    @Test
    fun `5xx 는 재시도 후 성공한다(재시도+백오프)`() {
        val builder = RestClient.builder().baseUrl(props.baseUrl)
        val server = MockRestServiceServer.bindTo(builder).build()
        // 1차 실패 → 2차 성공 (maxRetries=2 이므로 충분)
        server.expect(ExpectedCount.once(), requestTo("https://api.anthropic.com/v1/messages"))
            .andRespond(withServerError())
        server.expect(ExpectedCount.once(), requestTo("https://api.anthropic.com/v1/messages"))
            .andRespond(withSuccess(okBody(), MediaType.APPLICATION_JSON))

        val draft = newClient(builder).generateDraft(LlmDraftCommand("성남시 10% 할인 7월", null))
        draft.target shouldBe "SN"
        server.verify()
    }

    @Test
    fun `재시도 소진 시 결정적 에러로 변환된다`() {
        val builder = RestClient.builder().baseUrl(props.baseUrl)
        val server = MockRestServiceServer.bindTo(builder).build()
        server.expect(ExpectedCount.times(props.maxRetries + 1), requestTo("https://api.anthropic.com/v1/messages"))
            .andRespond(withServerError())

        val ex = shouldThrow<BusinessException> {
            newClient(builder).generateDraft(LlmDraftCommand("성남시 10% 할인 7월", null))
        }
        ex.errorCode shouldBe ErrorCode.AI_DRAFT_GENERATION_FAILED
        server.verify()
    }

    @Test
    fun `4xx 는 재시도 없이 즉시 실패한다`() {
        val builder = RestClient.builder().baseUrl(props.baseUrl)
        val server = MockRestServiceServer.bindTo(builder).build()
        server.expect(ExpectedCount.once(), requestTo("https://api.anthropic.com/v1/messages"))
            .andRespond(withBadRequest())

        val ex = shouldThrow<BusinessException> {
            newClient(builder).generateDraft(LlmDraftCommand("성남시 10% 할인 7월", null))
        }
        ex.errorCode shouldBe ErrorCode.AI_DRAFT_GENERATION_FAILED
        server.verify() // 단 1회만 호출됐음을 확인 — 재시도 없음
    }

    @Test
    fun `파싱 실패는 재시도 없이 즉시 실패한다`() {
        val malformedBody = """
            {"id":"m","type":"message","role":"assistant","model":"claude-haiku-4-5",
             "stop_reason":"end_turn",
             "content":[{"type":"text","text":"{\"oops\":\"required fields missing\"}"}],
             "usage":{"input_tokens":800,"output_tokens":120}}
        """.trimIndent()
        val builder = RestClient.builder().baseUrl(props.baseUrl)
        val server = MockRestServiceServer.bindTo(builder).build()
        server.expect(ExpectedCount.once(), requestTo("https://api.anthropic.com/v1/messages"))
            .andRespond(withSuccess(malformedBody, MediaType.APPLICATION_JSON))

        val ex = shouldThrow<BusinessException> {
            newClient(builder).generateDraft(LlmDraftCommand("성남시 10% 할인 7월", null))
        }
        ex.errorCode shouldBe ErrorCode.AI_DRAFT_GENERATION_FAILED
        server.verify() // 단 1회만 호출됐음을 확인 — 파싱 실패는 재시도 안 함
    }

    @Test
    fun `서킷이 열리면 호출 없이 빠른 실패한다`() {
        val builder = RestClient.builder().baseUrl(props.baseUrl)
        val server = MockRestServiceServer.bindTo(builder).build()
        server.expect(ExpectedCount.never(), requestTo("https://api.anthropic.com/v1/messages"))

        val cb = SimpleCircuitBreaker(failureThreshold = 1, openMillis = 10_000)
        cb.recordFailure() // 미리 오픈
        val ex = shouldThrow<BusinessException> {
            newClient(builder, cb).generateDraft(LlmDraftCommand("성남시 10% 할인 7월", null))
        }
        ex.errorCode shouldBe ErrorCode.AI_DRAFT_GENERATION_FAILED
        server.verify() // 서버는 호출되지 않음
    }
}
