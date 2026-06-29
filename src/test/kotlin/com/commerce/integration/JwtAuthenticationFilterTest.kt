package com.commerce.integration

import com.commerce.config.JwtTokenProvider
import com.commerce.support.IntegrationTestSupport
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get

@AutoConfigureMockMvc
class JwtAuthenticationFilterTest : IntegrationTestSupport() {

    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var jwtTokenProvider: JwtTokenProvider

    @Test
    fun `protected endpoint returns 401 without a token`() {
        mockMvc.get("/api/v1/me")
            .andExpect { status { isUnauthorized() } }
    }

    @Test
    fun `protected endpoint returns 200 and resolves memberId from a valid token`() {
        val token = jwtTokenProvider.generateToken(777L, "USER")
        mockMvc.get("/api/v1/me") {
            header("Authorization", "Bearer $token")
        }.andExpect {
            status { isOk() }
            jsonPath("$.memberId") { value(777) }
        }
    }

    @Test
    fun `protected endpoint returns 401 with a malformed token`() {
        mockMvc.get("/api/v1/me") {
            header("Authorization", "Bearer not-a-real-jwt")
        }.andExpect { status { isUnauthorized() } }
    }
}
