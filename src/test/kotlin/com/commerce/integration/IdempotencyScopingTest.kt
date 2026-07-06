package com.commerce.integration

import com.commerce.cart.application.CartService
import com.commerce.config.JwtTokenProvider
import com.commerce.order.infrastructure.OrderJpaRepository
import com.commerce.support.TestFixtures
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.MySQLContainer
import org.testcontainers.junit.jupiter.Testcontainers
import java.math.BigDecimal
import java.util.UUID

/**
 * 멱등키가 인증 주체(memberId)에 스코프되는지 검증한다.
 * 서로 다른 회원이 우연히/악의적으로 같은 Idempotency-Key를 보내도, 각자 자신의 주문이 처리되어야 하며
 * 한쪽이 다른 쪽의 캐시된 응답을 받거나(응답 유출) 정상 요청이 409로 거절되어선 안 된다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Testcontainers
class IdempotencyScopingTest {

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
    @Autowired lateinit var orderRepository: OrderJpaRepository
    @Autowired lateinit var jwtTokenProvider: JwtTokenProvider

    @Test
    fun `same Idempotency-Key from different members places each member's own order`() {
        val seller = fixtures.createSeller(fixtures.createMember())
        val skuId = fixtures.createOnSaleSku(seller.id, BigDecimal("50000"), 10)
        val buyerA = fixtures.createMember()
        val buyerB = fixtures.createMember()
        cartService.addItem(buyerA.id, skuId, 1)
        cartService.addItem(buyerB.id, skuId, 1)

        // 두 회원이 동일한 키 문자열을 사용한다.
        val sharedKey = UUID.randomUUID().toString()
        val responseA = checkout(sharedKey, buyerA.id)
        val responseB = checkout(sharedKey, buyerB.id)

        // 둘 다 자기 주문이 성공(201)해야 한다 — B가 A의 캐시 응답을 받거나 409로 막히면 실패.
        responseA.statusCode.value() shouldBe 201
        responseB.statusCode.value() shouldBe 201
        orderRepository.findByMemberId(buyerA.id).size shouldBe 1
        orderRepository.findByMemberId(buyerB.id).size shouldBe 1
    }

    private fun checkout(idempotencyKey: String, memberId: Long): ResponseEntity<String> {
        val headers = HttpHeaders().apply {
            contentType = MediaType.APPLICATION_JSON
            set("Idempotency-Key", idempotencyKey)
            set("Authorization", "Bearer ${jwtTokenProvider.generateToken(memberId, "USER")}")
        }
        return restTemplate.postForEntity("/api/v1/orders", HttpEntity<Void>(headers), String::class.java)
    }
}
