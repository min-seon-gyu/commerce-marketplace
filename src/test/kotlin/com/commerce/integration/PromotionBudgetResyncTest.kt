package com.commerce.integration

import com.commerce.cart.application.CartService
import com.commerce.order.application.OrderService
import com.commerce.promotion.domain.DiscountType
import com.commerce.promotion.infrastructure.PromotionBudgetManager
import com.commerce.promotion.infrastructure.PromotionBudgetResyncScheduler
import com.commerce.support.IntegrationTestSupport
import com.commerce.support.TestFixtures
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.math.BigDecimal

/**
 * 프로모션 예산 신뢰성 검증.
 * - release는 하한 0으로 클램프해 음수 카운터를 만들지 않는다.
 * - 재동기화는 Redis 카운터 유실 후 DB 진실원천(비취소 쿠폰 주문의 할인 합)으로 복원하며, 취소 주문은 제외한다.
 */
class PromotionBudgetResyncTest : IntegrationTestSupport() {

    @Autowired lateinit var fixtures: TestFixtures
    @Autowired lateinit var orderService: OrderService
    @Autowired lateinit var cartService: CartService
    @Autowired lateinit var budgetManager: PromotionBudgetManager
    @Autowired lateinit var resyncScheduler: PromotionBudgetResyncScheduler

    @Test
    fun `release clamps counter at zero instead of going negative`() {
        val promo = fixtures.createPromotion(discountType = DiscountType.FIXED, discountValue = BigDecimal("3000"))

        // 예약 없이 반환해도 카운터가 음수로 내려가지 않아야 한다(Redis 유실 후 보상 등의 경로 방어).
        budgetManager.release(promo.id, BigDecimal("5000"))

        budgetManager.consumed(promo.id) shouldBe 0L
    }

    @Test
    fun `resync restores consumed budget from DB and excludes cancelled orders`() {
        val owner = fixtures.createMember()
        val seller = fixtures.createSeller(owner)
        val promo = fixtures.createPromotion(
            discountType = DiscountType.FIXED, discountValue = BigDecimal("3000"),
            budgetLimit = BigDecimal("1000000"),
        )

        // buyer1: 쿠폰 주문 유지(예산 3,000 소비)
        val buyer1 = fixtures.createMember()
        val sku1 = fixtures.createOnSaleSku(seller.id, BigDecimal("20000"), 100)
        cartService.addItem(buyer1.id, sku1, 1)
        orderService.placeOrder(buyer1.id, fixtures.issueCoupon(promo.id, buyer1.id).id)

        // buyer2: 쿠폰 주문 후 전체취소(예산 반환 → DB 진실원천에서 제외)
        val buyer2 = fixtures.createMember()
        val sku2 = fixtures.createOnSaleSku(seller.id, BigDecimal("20000"), 100)
        cartService.addItem(buyer2.id, sku2, 1)
        val order2 = orderService.placeOrder(buyer2.id, fixtures.issueCoupon(promo.id, buyer2.id).id)
        orderService.cancelOrder(buyer2.id, order2.id)

        budgetManager.consumed(promo.id) shouldBe 3000L // 3,000 + 3,000 − 3,000(취소 반환)

        // Redis 카운터 유실 시뮬레이션 → 0
        budgetManager.resync(promo.id, 0L)
        budgetManager.consumed(promo.id) shouldBe 0L

        // DB 기준 재동기화 → 비취소 주문(buyer1)의 할인 3,000만 복원
        resyncScheduler.resyncAll()
        budgetManager.consumed(promo.id) shouldBe 3000L
    }
}
