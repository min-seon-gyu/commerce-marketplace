package com.commerce.integration

import com.commerce.cart.application.CartService
import com.commerce.order.application.OrderService
import com.commerce.support.IntegrationTestSupport
import com.commerce.support.QueryCountConfig
import com.commerce.support.SqlCounter
import com.commerce.support.TestFixtures
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import java.math.BigDecimal

/**
 * 성능 실증 #1 — 체크아웃 fan-out N+1 제거.
 * placeOrder는 카트 항목별 findById(sku)+findById(product)를 IN 조회 2건으로 일괄 로드한다.
 * 카트 크기와 무관하게 skus/products SELECT가 **각각 1건**(상수)임을 증명한다(개선 전에는 항목당 1건 = N).
 */
@Import(QueryCountConfig::class)
class CheckoutQueryCountTest : IntegrationTestSupport() {

    @Autowired lateinit var fixtures: TestFixtures
    @Autowired lateinit var orderService: OrderService
    @Autowired lateinit var cartService: CartService
    @Autowired lateinit var sqlCounter: SqlCounter

    private fun buyerWithCart(itemCount: Int): Long {
        val owner = fixtures.createMember()
        val seller = fixtures.createSeller(owner)
        val buyer = fixtures.createMember()
        repeat(itemCount) { i ->
            val skuId = fixtures.createOnSaleSku(seller.id, BigDecimal("10000") + i.toBigDecimal(), 100)
            cartService.addItem(buyer.id, skuId, 1)
        }
        return buyer.id
    }

    private fun countOnCheckout(itemCount: Int): Pair<Int, Int> {
        val buyerId = buyerWithCart(itemCount)
        sqlCounter.reset() // 셋업(상품·카트 생성) 이후부터 측정
        orderService.placeOrder(buyerId)
        return sqlCounter.selects("skus") to sqlCounter.selects("products")
    }

    @Test
    fun `checkout loads skus and products in constant queries regardless of cart size`() {
        // 카트 3개 → skus 1건, products 1건 (N+1이면 3건씩)
        val (skus3, products3) = countOnCheckout(3)
        skus3 shouldBe 1
        products3 shouldBe 1

        // 카트 6개여도 여전히 1건씩 — 항목 수에 비례하지 않음(상수)
        val (skus6, products6) = countOnCheckout(6)
        skus6 shouldBe 1
        products6 shouldBe 1
    }
}
