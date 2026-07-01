package com.commerce.product.application

import com.commerce.common.exception.BusinessException
import com.commerce.common.exception.ErrorCode
import com.commerce.inventory.application.StockService
import com.commerce.product.domain.Product
import com.commerce.product.domain.ProductCategory
import com.commerce.product.domain.ProductStatus
import com.commerce.product.domain.Sku
import com.commerce.product.infrastructure.ProductJpaRepository
import com.commerce.product.infrastructure.SkuJpaRepository
import com.commerce.seller.infrastructure.SellerJpaRepository
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal

/** 상품 등록 시 SKU 사양(옵션·가격·초기재고). */
data class SkuSpec(
    val skuCode: String,
    val optionName: String,
    val options: Map<String, String>,
    val price: BigDecimal,
    val initialStock: Int,
)

/** SKU + 현재 재고 뷰. */
data class SkuStockView(val sku: Sku, val quantity: Int)

/** 상품 상세(상품 + SKU/재고). */
data class ProductDetail(val product: Product, val skus: List<SkuStockView>)

@Service
class ProductService(
    private val productRepository: ProductJpaRepository,
    private val skuRepository: SkuJpaRepository,
    private val stockService: StockService,
    private val sellerRepository: SellerJpaRepository,
    private val objectMapper: ObjectMapper,
) {

    /** 상품 + SKU + 초기재고를 한 트랜잭션에서 원자적으로 등록한다. 요청자는 해당 판매자의 소유주여야 한다. */
    @Transactional
    fun createProduct(
        requesterMemberId: Long,
        sellerId: Long,
        name: String,
        description: String?,
        category: ProductCategory,
        skus: List<SkuSpec>,
    ): Product {
        require(skus.isNotEmpty()) { "상품에는 최소 1개의 SKU가 필요합니다" }
        val seller = sellerRepository.findById(sellerId)
            .orElseThrow { BusinessException(ErrorCode.ENTITY_NOT_FOUND) }
        if (seller.owner.id != requesterMemberId) throw BusinessException(ErrorCode.ACCESS_DENIED)
        if (!seller.isApproved()) throw BusinessException(ErrorCode.SELLER_NOT_APPROVED)

        val product = productRepository.save(Product(sellerId, name, description, category))
        skus.forEach { spec ->
            if (skuRepository.existsBySkuCode(spec.skuCode)) throw BusinessException(ErrorCode.DUPLICATE_SKU_CODE)
            require(spec.price > BigDecimal.ZERO) { "SKU 가격은 양수여야 합니다" }
            val sku = skuRepository.save(
                Sku(
                    productId = product.id,
                    skuCode = spec.skuCode,
                    optionName = spec.optionName,
                    options = objectMapper.writeValueAsString(spec.options),
                    price = spec.price,
                )
            )
            stockService.createStock(sku.id, spec.initialStock)
        }
        return product
    }

    /** 판매 개시(DRAFT/SUSPENDED → ON_SALE). 소유주만. */
    @Transactional
    fun onSale(requesterMemberId: Long, productId: Long): Product {
        val product = getOwnedProduct(requesterMemberId, productId)
        product.onSale()
        return product
    }

    @Transactional(readOnly = true)
    fun getDetail(productId: Long): ProductDetail {
        val product = productRepository.findById(productId)
            .orElseThrow { BusinessException(ErrorCode.ENTITY_NOT_FOUND) }
        val skus = skuRepository.findByProductId(productId)
        val quantities = stockService.quantitiesBySkuIds(skus.map { it.id })
        return ProductDetail(product, skus.map { SkuStockView(it, quantities[it.id] ?: 0) })
    }

    @Transactional(readOnly = true)
    fun listOnSale(pageable: Pageable): Page<Product> =
        productRepository.findByStatus(ProductStatus.ON_SALE, pageable)

    private fun getOwnedProduct(requesterMemberId: Long, productId: Long): Product {
        val product = productRepository.findById(productId)
            .orElseThrow { BusinessException(ErrorCode.ENTITY_NOT_FOUND) }
        val seller = sellerRepository.findById(product.sellerId)
            .orElseThrow { BusinessException(ErrorCode.ENTITY_NOT_FOUND) }
        if (seller.owner.id != requesterMemberId) throw BusinessException(ErrorCode.ACCESS_DENIED)
        return product
    }
}
