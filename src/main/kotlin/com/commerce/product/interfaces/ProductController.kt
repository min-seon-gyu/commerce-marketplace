package com.commerce.product.interfaces

import com.commerce.common.api.ApiResponse
import com.commerce.common.security.SecurityUtils
import com.commerce.product.application.ProductDetail
import com.commerce.product.application.ProductService
import com.commerce.product.application.SkuSpec
import com.commerce.product.domain.Product
import com.commerce.product.domain.ProductCategory
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.web.PageableDefault
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.*
import java.math.BigDecimal

data class SkuRequest(
    val skuCode: String,
    val optionName: String,
    val options: Map<String, String> = emptyMap(),
    val price: BigDecimal,
    val initialStock: Int,
)

data class CreateProductRequest(
    val sellerId: Long,
    val name: String,
    val description: String? = null,
    val category: ProductCategory,
    val skus: List<SkuRequest>,
)

data class ProductResponse(val id: Long, val sellerId: Long, val name: String, val category: String, val status: String) {
    companion object {
        fun from(p: Product) = ProductResponse(p.id, p.sellerId, p.name, p.category.name, p.status.name)
    }
}

data class SkuView(
    val id: Long,
    val skuCode: String,
    val optionName: String,
    val options: Map<String, String>,
    val price: BigDecimal,
    val quantity: Int,
)

data class ProductDetailResponse(
    val id: Long,
    val sellerId: Long,
    val name: String,
    val description: String?,
    val category: String,
    val status: String,
    val skus: List<SkuView>,
)

@RestController
@RequestMapping("/api/v1/products")
class ProductController(
    private val productService: ProductService,
    private val objectMapper: ObjectMapper,
    private val cache: ProductDetailCache,
) {

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(@RequestBody request: CreateProductRequest): ApiResponse<ProductResponse> {
        val product = productService.createProduct(
            requesterMemberId = SecurityUtils.currentMemberId(),
            sellerId = request.sellerId,
            name = request.name,
            description = request.description,
            category = request.category,
            skus = request.skus.map {
                SkuSpec(it.skuCode, it.optionName, it.options, it.price, it.initialStock)
            },
        )
        return ApiResponse.ok(ProductResponse.from(product))
    }

    @PostMapping("/{id}/on-sale")
    fun onSale(@PathVariable id: Long): ApiResponse<ProductResponse> {
        val response = ApiResponse.ok(ProductResponse.from(productService.onSale(SecurityUtils.currentMemberId(), id)))
        cache.evict(id) // 상태 변경(커밋) 후 캐시 무효화 — Redis 장애 시에도 응답에 영향 없음
        return response
    }

    /**
     * 상품 상세 조회. 읽기 편중·저변경 특성이라 Redis 캐시-어사이드(TTL 25~35s 지터)로 응답을 캐싱한다.
     * 캐시는 best-effort([ProductDetailCache]) — Redis 장애 시 DB로 폴백해 읽기 가용성을 유지한다.
     * 재고는 최대 TTL만큼 지연될 수 있으나 카탈로그 열람엔 무방하며, 정확한 재고는 체크아웃 시 락으로 강제한다.
     */
    @GetMapping("/{id}")
    fun getDetail(@PathVariable id: Long): ApiResponse<ProductDetailResponse> {
        cache.get(id)?.let { return ApiResponse.ok(it) }
        val response = toDetailResponse(productService.getDetail(id))
        cache.put(id, response)
        return ApiResponse.ok(response)
    }

    @GetMapping
    fun list(@PageableDefault(size = 20) pageable: Pageable): ApiResponse<Page<ProductResponse>> =
        ApiResponse.ok(productService.listOnSale(pageable).map { ProductResponse.from(it) })

    @Suppress("UNCHECKED_CAST")
    private fun parseOptions(json: String): Map<String, String> =
        try { objectMapper.readValue(json, Map::class.java) as Map<String, String> } catch (e: Exception) { emptyMap() }

    private fun toDetailResponse(detail: ProductDetail): ProductDetailResponse {
        val p = detail.product
        return ProductDetailResponse(
            id = p.id,
            sellerId = p.sellerId,
            name = p.name,
            description = p.description,
            category = p.category.name,
            status = p.status.name,
            skus = detail.skus.map {
                SkuView(it.sku.id, it.sku.skuCode, it.sku.optionName, parseOptions(it.sku.options), it.sku.price, it.quantity)
            },
        )
    }
}
