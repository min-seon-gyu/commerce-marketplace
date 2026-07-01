package com.commerce.integration

import com.commerce.common.exception.BusinessException
import com.commerce.common.exception.ErrorCode
import com.commerce.product.application.ProductService
import com.commerce.product.application.SkuSpec
import com.commerce.product.domain.ProductCategory
import com.commerce.product.domain.ProductStatus
import com.commerce.support.IntegrationTestSupport
import com.commerce.support.TestFixtures
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.math.BigDecimal

/**
 * 상품(카탈로그) + SKU + 재고 등록/조회 검증.
 * 상품 등록 시 SKU와 초기 재고가 원자적으로 생성되고, 상세 조회로 SKU별 재고가 함께 반환된다.
 */
class ProductInventoryTest : IntegrationTestSupport() {

    @Autowired lateinit var fixtures: TestFixtures
    @Autowired lateinit var productService: ProductService

    private fun sku(code: String, stock: Int) =
        SkuSpec(code, "블랙/대형", mapOf("색상" to "블랙", "크기" to "대형"), BigDecimal("299000"), stock)

    @Test
    fun `product with skus and initial stock is created and retrievable`() {
        val region = fixtures.createRegion()
        val owner = fixtures.createMember()
        val seller = fixtures.createSeller(region, owner) // APPROVED

        val product = productService.createProduct(
            requesterMemberId = owner.id,
            sellerId = seller.id,
            name = "3인용 소파",
            description = "패브릭 소파",
            category = ProductCategory.FURNITURE,
            skus = listOf(sku("SOFA-BLK-${owner.id}", 10), sku("SOFA-WHT-${owner.id}", 3)),
        )
        product.status shouldBe ProductStatus.DRAFT

        val detail = productService.getDetail(product.id)
        detail.skus.size shouldBe 2
        detail.skus.map { it.quantity }.sorted() shouldBe listOf(3, 10)
        detail.skus.first().sku.optionName shouldBe "블랙/대형"

        // 판매 개시
        productService.onSale(owner.id, product.id).status shouldBe ProductStatus.ON_SALE
    }

    @Test
    fun `duplicate sku code is rejected`() {
        val region = fixtures.createRegion()
        val owner = fixtures.createMember()
        val seller = fixtures.createSeller(region, owner)
        val dup = "DUP-${owner.id}"

        val ex = shouldThrow<BusinessException> {
            productService.createProduct(
                owner.id, seller.id, "중복SKU상품", null, ProductCategory.DECO,
                listOf(sku(dup, 5), sku(dup, 5)),
            )
        }
        ex.errorCode shouldBe ErrorCode.DUPLICATE_SKU_CODE
    }

    @Test
    fun `non-owner cannot create product for a seller`() {
        val region = fixtures.createRegion()
        val owner = fixtures.createMember()
        val seller = fixtures.createSeller(region, owner)
        val stranger = fixtures.createMember()

        val ex = shouldThrow<BusinessException> {
            productService.createProduct(
                stranger.id, seller.id, "무단상품", null, ProductCategory.OTHER,
                listOf(sku("X-${stranger.id}", 1)),
            )
        }
        ex.errorCode shouldBe ErrorCode.ACCESS_DENIED
    }
}
