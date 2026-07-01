package com.commerce.integration

import com.commerce.cart.application.CartService
import com.commerce.config.JwtTokenProvider
import com.commerce.inventory.application.StockService
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
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.MySQLContainer
import org.testcontainers.junit.jupiter.Testcontainers
import java.math.BigDecimal
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Testcontainers
class IdempotencyConcurrencyTest {

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
    @Autowired lateinit var stockService: StockService
    @Autowired lateinit var orderRepository: OrderJpaRepository
    @Autowired lateinit var jwtTokenProvider: JwtTokenProvider

    /**
     * 같은 Idempotency-Key로 동시에 여러 번 체크아웃 요청이 들어와도, 멱등성은 "정확히 1회"만 처리해야 한다.
     * (동시 요청이 모두 통과하면 이중 주문/이중 재고차감이 발생)
     */
    @Test
    fun `same Idempotency-Key concurrent checkout should place exactly one order`() {
        val region = fixtures.createRegion(code = UUID.randomUUID().toString().take(2).uppercase())
        val seller = fixtures.createSeller(region, fixtures.createMember())
        val buyer = fixtures.createMember()
        val skuId = fixtures.createOnSaleSku(seller.id, BigDecimal("50000"), 5)
        cartService.addItem(buyer.id, skuId, 1)

        val idempotencyKey = UUID.randomUUID().toString()
        val threadCount = 10
        val headers = HttpHeaders().apply {
            contentType = MediaType.APPLICATION_JSON
            set("Idempotency-Key", idempotencyKey)
            set("Authorization", "Bearer ${jwtTokenProvider.generateToken(buyer.id, "USER")}")
        }
        val request = HttpEntity<Void>(headers)
        val url = "/api/v1/orders"

        val latch = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(threadCount)
        val success2xx = AtomicInteger(0)
        val futures = (1..threadCount).map {
            executor.submit {
                latch.await()
                val response = restTemplate.postForEntity(url, request, String::class.java)
                if (response.statusCode.is2xxSuccessful) success2xx.incrementAndGet()
            }
        }
        latch.countDown()
        futures.forEach { it.get() }
        executor.shutdown()

        // 멱등키가 같으므로 주문은 정확히 1건만 생성되고 재고는 1개만 차감되어야 한다.
        orderRepository.findByMemberId(buyer.id).size shouldBe 1
        stockService.getBySkuId(skuId).quantity shouldBe 4
    }
}
