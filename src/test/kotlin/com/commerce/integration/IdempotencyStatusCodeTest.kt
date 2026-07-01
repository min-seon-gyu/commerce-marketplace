package com.commerce.integration

import com.commerce.cart.application.CartService
import com.commerce.config.JwtTokenProvider
import com.commerce.support.TestFixtures
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.MySQLContainer
import org.testcontainers.junit.jupiter.Testcontainers
import java.math.BigDecimal
import java.util.UUID

/**
 * 멱등 재시도 시 원래 응답의 상태코드가 보존되는지 검증한다.
 * 주문 체크아웃(POST /orders)은 @ResponseStatus(201 CREATED) — 재시도도 201을 반환해야 한다(캐시 status 보존).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Testcontainers
class IdempotencyStatusCodeTest {

    companion object {
        val mysql = MySQLContainer("mysql:8.0").apply {
            withDatabaseName("voucher_test")
            withUsername("test")
            withPassword("test")
            withCommand("--character-set-server=utf8mb4", "--collation-server=utf8mb4_unicode_ci")
            start()
        }
        val redis = GenericContainer<Nothing>("redis:7-alpine").apply {
            withExposedPorts(6379)
            start()
        }

        @JvmStatic
        @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { mysql.jdbcUrl }
            registry.add("spring.datasource.username") { mysql.username }
            registry.add("spring.datasource.password") { mysql.password }
            registry.add("spring.data.redis.host") { redis.host }
            registry.add("spring.data.redis.port") { redis.getMappedPort(6379).toString() }
        }
    }

    @Autowired lateinit var restTemplate: TestRestTemplate
    @Autowired lateinit var fixtures: TestFixtures
    @Autowired lateinit var cartService: CartService
    @Autowired lateinit var jwtTokenProvider: JwtTokenProvider

    @Test
    fun `idempotent replay preserves original 201 CREATED status`() {
        val seller = fixtures.createSeller(fixtures.createMember())
        val buyer = fixtures.createMember()
        val skuId = fixtures.createOnSaleSku(seller.id, BigDecimal("50000"), 5)
        cartService.addItem(buyer.id, skuId, 1)

        val headers = HttpHeaders().apply {
            contentType = MediaType.APPLICATION_JSON
            set("Idempotency-Key", UUID.randomUUID().toString())
            set("Authorization", "Bearer ${jwtTokenProvider.generateToken(buyer.id, "USER")}")
        }
        val request = HttpEntity<Void>(headers)
        val url = "/api/v1/orders"

        val first = restTemplate.postForEntity(url, request, String::class.java)
        val replay = restTemplate.postForEntity(url, request, String::class.java)

        first.statusCode.value() shouldBe 201
        // 재시도는 캐시된 응답을 반환하되 원래 상태코드(201)를 보존해야 한다.
        replay.statusCode.value() shouldBe 201
    }
}
