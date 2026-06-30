package com.commerce.promotion

import com.commerce.promotion.infrastructure.PromotionBudgetManager
import com.commerce.support.IntegrationTestSupport
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.math.BigDecimal
import kotlin.random.Random

class PromotionBudgetManagerTest : IntegrationTestSupport() {

    @Autowired lateinit var budgetManager: PromotionBudgetManager

    @Test
    fun `reserve within limit succeeds and accumulates consumed`() {
        val promotionId = Random.nextLong(1, 1_000_000_000)
        budgetManager.reserve(promotionId, BigDecimal("3000"), BigDecimal("9000")) shouldBe true
        budgetManager.reserve(promotionId, BigDecimal("3000"), BigDecimal("9000")) shouldBe true
        budgetManager.consumed(promotionId) shouldBe 6000L
    }

    @Test
    fun `reserve exceeding limit fails and does not consume (atomic rollback)`() {
        val promotionId = Random.nextLong(1, 1_000_000_000)
        budgetManager.reserve(promotionId, BigDecimal("8000"), BigDecimal("9000")) shouldBe true
        budgetManager.reserve(promotionId, BigDecimal("3000"), BigDecimal("9000")) shouldBe false
        budgetManager.consumed(promotionId) shouldBe 8000L // 두 번째 예약은 롤백되어 미반영
    }

    @Test
    fun `release returns budget (compensating DECRBY)`() {
        val promotionId = Random.nextLong(1, 1_000_000_000)
        budgetManager.reserve(promotionId, BigDecimal("3000"), BigDecimal("9000")) shouldBe true
        budgetManager.release(promotionId, BigDecimal("3000"))
        budgetManager.consumed(promotionId) shouldBe 0L
    }
}
