package com.commerce.integration

import com.commerce.config.JwtTokenProvider
import com.commerce.support.IntegrationTestSupport
import com.commerce.support.TestFixtures
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import java.math.BigDecimal

/**
 * 특권(ADMIN) 엔드포인트가 시큐리티 필터 체인에서 실제로 보호되는지 검증한다.
 * - 토큰 없음 → 401
 * - USER 역할 → 403
 * - ADMIN 역할 → 통과(200)
 * 그리고 공개 엔드포인트는 여전히 토큰 없이 접근 가능해야 한다(과잠금 회귀 방지).
 */
@AutoConfigureMockMvc
class AdminAuthorizationTest : IntegrationTestSupport() {

    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var jwtTokenProvider: JwtTokenProvider
    @Autowired lateinit var fixtures: TestFixtures

    @Test
    fun `admin ledger endpoint without token returns 401`() {
        mockMvc.get("/api/v1/admin/ledger/balance/global")
            .andExpect { status { isUnauthorized() } }
    }

    @Test
    fun `admin ledger endpoint with USER role returns 403`() {
        val token = jwtTokenProvider.generateToken(1L, "USER")
        mockMvc.get("/api/v1/admin/ledger/balance/global") {
            header("Authorization", "Bearer $token")
        }.andExpect { status { isForbidden() } }
    }

    @Test
    fun `admin ledger endpoint with ADMIN role returns 200`() {
        val token = jwtTokenProvider.generateToken(1L, "ADMIN")
        mockMvc.get("/api/v1/admin/ledger/balance/global") {
            header("Authorization", "Bearer $token")
        }.andExpect { status { isOk() } }
    }

    @Test
    fun `member suspend without token returns 401`() {
        mockMvc.post("/api/v1/members/1/suspend")
            .andExpect { status { isUnauthorized() } }
    }

    @Test
    fun `member suspend with USER role returns 403`() {
        val token = jwtTokenProvider.generateToken(1L, "USER")
        mockMvc.post("/api/v1/members/1/suspend") {
            header("Authorization", "Bearer $token")
        }.andExpect { status { isForbidden() } }
    }

    @Test
    fun `settlement calculate without token returns 401`() {
        mockMvc.post("/api/v1/settlements/calculate") {
            contentType = org.springframework.http.MediaType.APPLICATION_JSON
            content = """{"sellerId": 1}"""
        }.andExpect { status { isUnauthorized() } }
    }

    @Test
    fun `public product list stays accessible without token`() {
        // 과잠금 회귀 방지: 공개 조회 엔드포인트(상품 목록)는 토큰 없이도 접근 가능해야 한다.
        mockMvc.get("/api/v1/products")
            .andExpect { status { isOk() } }
    }

    @Test
    fun `promotion create without token returns 401`() {
        mockMvc.post("/api/v1/promotions") {
            contentType = org.springframework.http.MediaType.APPLICATION_JSON
            content = "{}"
        }.andExpect { status { isUnauthorized() } }
    }

    @Test
    fun `promotion create with USER role returns 403`() {
        val token = jwtTokenProvider.generateToken(1L, "USER")
        mockMvc.post("/api/v1/promotions") {
            header("Authorization", "Bearer $token")
            contentType = org.springframework.http.MediaType.APPLICATION_JSON
            content = "{}"
        }.andExpect { status { isForbidden() } }
    }

    @Test
    fun `seller register without token returns 401`() {
        mockMvc.post("/api/v1/sellers") {
            contentType = org.springframework.http.MediaType.APPLICATION_JSON
            content = "{}"
        }.andExpect { status { isUnauthorized() } }
    }

    // ── 결제 거래 조회 IDOR 차단 (묶음 A) ─────────────────────────────────────

    @Test
    fun `transaction without token returns 401`() {
        mockMvc.get("/api/v1/transactions/1")
            .andExpect { status { isUnauthorized() } }
    }

    @Test
    fun `transaction owned by another member returns 403`() {
        val owner = fixtures.createMember()
        val seller = fixtures.createSeller(owner)
        val buyer = fixtures.createMember()
        val txId = fixtures.sellerSale(buyer.id, seller.id, BigDecimal("10000")).paymentTransactionId!!
        val attackerToken = jwtTokenProvider.generateToken(fixtures.createMember().id, "USER")

        mockMvc.get("/api/v1/transactions/$txId") {
            header("Authorization", "Bearer $attackerToken")
        }.andExpect {
            status { isForbidden() }
            jsonPath("$.error.code") { value("ACCESS_DENIED") }
        }
    }

    @Test
    fun `transaction owned by self returns 200`() {
        val owner = fixtures.createMember()
        val seller = fixtures.createSeller(owner)
        val buyer = fixtures.createMember()
        val txId = fixtures.sellerSale(buyer.id, seller.id, BigDecimal("10000")).paymentTransactionId!!

        mockMvc.get("/api/v1/transactions/$txId") {
            header("Authorization", "Bearer ${jwtTokenProvider.generateToken(buyer.id, "USER")}")
        }.andExpect {
            status { isOk() }
            jsonPath("$.data.id") { value(txId) }
        }
    }

    @Test
    fun `transaction with ADMIN role returns 200`() {
        val owner = fixtures.createMember()
        val seller = fixtures.createSeller(owner)
        val buyer = fixtures.createMember()
        val txId = fixtures.sellerSale(buyer.id, seller.id, BigDecimal("10000")).paymentTransactionId!!

        mockMvc.get("/api/v1/transactions/$txId") {
            header("Authorization", "Bearer ${jwtTokenProvider.generateToken(999_999L, "ADMIN")}")
        }.andExpect { status { isOk() } }
    }

    // ── 회원 PII 조회 IDOR 차단 (묶음 A) ──────────────────────────────────────

    @Test
    fun `member detail without token returns 401`() {
        mockMvc.get("/api/v1/members/1")
            .andExpect { status { isUnauthorized() } }
    }

    @Test
    fun `member detail of another member returns 403`() {
        val victim = fixtures.createMember()
        val attackerToken = jwtTokenProvider.generateToken(fixtures.createMember().id, "USER")

        mockMvc.get("/api/v1/members/${victim.id}") {
            header("Authorization", "Bearer $attackerToken")
        }.andExpect {
            status { isForbidden() }
            jsonPath("$.error.code") { value("ACCESS_DENIED") }
        }
    }

    @Test
    fun `own member detail returns 200`() {
        val member = fixtures.createMember()

        mockMvc.get("/api/v1/members/${member.id}") {
            header("Authorization", "Bearer ${jwtTokenProvider.generateToken(member.id, "USER")}")
        }.andExpect {
            status { isOk() }
            jsonPath("$.data.email") { value(member.email) }
        }
    }

    @Test
    fun `member detail with ADMIN role returns 200`() {
        val member = fixtures.createMember()

        mockMvc.get("/api/v1/members/${member.id}") {
            header("Authorization", "Bearer ${jwtTokenProvider.generateToken(999_999L, "ADMIN")}")
        }.andExpect { status { isOk() } }
    }

    // ── 과잠금 회귀 방지: 기본 폐쇄 전환 후에도 공개 GET은 인증 없이 도달 가능 ──
    // (데이터 유무와 무관하게 인증 필터가 막지 않음을 확인 — 미존재 시 404이지 401이 아니어야 한다.)

    @Test
    fun `public product detail is not blocked by auth`() {
        mockMvc.get("/api/v1/products/999999")
            .andExpect { status { isNotFound() } }
    }

    @Test
    fun `public promotion detail is not blocked by auth`() {
        mockMvc.get("/api/v1/promotions/999999")
            .andExpect { status { isNotFound() } }
    }

    @Test
    fun `public seller detail is not blocked by auth`() {
        mockMvc.get("/api/v1/sellers/999999")
            .andExpect { status { isNotFound() } }
    }
}
