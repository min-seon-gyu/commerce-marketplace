package com.commerce.integration

import com.commerce.config.JwtTokenProvider
import com.commerce.member.application.MemberService
import com.commerce.support.IntegrationTestSupport
import com.commerce.support.TestFixtures
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.get

/**
 * 정지/탈퇴 회원의 기존 토큰이 즉시 무효화되는지 검증한다(Redis 블랙리스트 + JwtAuthenticationFilter).
 * suspend/withdraw의 AFTER_COMMIT 리스너가 블랙리스트에 등재하면, 필터가 해당 회원의 토큰을 거부해야 한다.
 */
@AutoConfigureMockMvc
class SuspendedMemberAuthTest : IntegrationTestSupport() {

    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var jwtTokenProvider: JwtTokenProvider
    @Autowired lateinit var memberService: MemberService
    @Autowired lateinit var fixtures: TestFixtures

    private fun callMe(token: String): ResultActionsDsl =
        mockMvc.get("/api/v1/me") { header("Authorization", "Bearer $token") }

    @Test
    fun `suspended member's existing token is rejected`() {
        val member = fixtures.createMember()
        val token = jwtTokenProvider.generateToken(member.id, "USER")
        callMe(token).andExpect { status { isOk() } } // 정지 전엔 유효

        memberService.suspend(member.id)               // 정지 → AFTER_COMMIT 블랙리스트 등재

        callMe(token).andExpect { status { isUnauthorized() } } // 기존 토큰 즉시 무효
    }

    @Test
    fun `withdrawn member's existing token is rejected`() {
        val member = fixtures.createMember()
        val token = jwtTokenProvider.generateToken(member.id, "USER")

        memberService.withdraw(member.id)

        callMe(token).andExpect { status { isUnauthorized() } }
    }

    @Test
    fun `unsuspended member's token works again`() {
        val member = fixtures.createMember()
        memberService.suspend(member.id)
        memberService.unsuspend(member.id) // 해제 → 블랙리스트에서 제거

        val token = jwtTokenProvider.generateToken(member.id, "USER")
        callMe(token).andExpect { status { isOk() } }
    }
}
