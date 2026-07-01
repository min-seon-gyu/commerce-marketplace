package com.commerce.integration

import com.commerce.common.exception.BusinessException
import com.commerce.common.exception.ErrorCode
import com.commerce.inventory.application.StockService
import com.commerce.product.application.ProductService
import com.commerce.product.application.SkuSpec
import com.commerce.product.domain.ProductCategory
import com.commerce.support.IntegrationTestSupport
import com.commerce.support.TestFixtures
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.math.BigDecimal
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * 재고 차감 동시성: 재고 5개 SKU에 10개 스레드가 동시에 1개씩 차감 시도 →
 * 정확히 5건만 성공하고 5건은 OUT_OF_STOCK, 최종 재고는 0(초과판매/음수 없음).
 * (분산락 + DB 비관적 락 이중 방어의 hot-key 검증 — voucher 결제 동시성 시나리오를 재고로 이식.)
 */
class InventoryConcurrencyTest : IntegrationTestSupport() {

    @Autowired lateinit var fixtures: TestFixtures
    @Autowired lateinit var productService: ProductService
    @Autowired lateinit var stockService: StockService

    @Test
    fun `concurrent deductions on same sku should not oversell`() {
        val owner = fixtures.createMember()
        val seller = fixtures.createSeller(owner)
        val product = productService.createProduct(
            owner.id, seller.id, "한정판 조명", null, ProductCategory.LIGHTING,
            listOf(SkuSpec("LAMP-LTD-${owner.id}", "단일", emptyMap(), BigDecimal("59000"), 5)),
        )
        val skuId = productService.getDetail(product.id).skus.first().sku.id

        val threadCount = 10
        val latch = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(threadCount)
        val success = AtomicInteger(0)
        val outOfStock = AtomicInteger(0)

        val futures = (1..threadCount).map {
            executor.submit {
                latch.await()
                try {
                    stockService.deduct(skuId, 1)
                    success.incrementAndGet()
                } catch (e: BusinessException) {
                    if (e.errorCode == ErrorCode.OUT_OF_STOCK) outOfStock.incrementAndGet()
                }
            }
        }
        latch.countDown()
        futures.forEach { it.get(30, TimeUnit.SECONDS) }
        executor.shutdown()

        success.get() shouldBe 5
        outOfStock.get() shouldBe 5
        stockService.getBySkuId(skuId).quantity shouldBe 0
    }
}
