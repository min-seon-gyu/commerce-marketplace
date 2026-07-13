package com.commerce.product.domain

import com.commerce.common.domain.BaseEntity
import com.commerce.common.exception.BusinessException
import com.commerce.common.exception.ErrorCode
import jakarta.persistence.*

enum class ProductCategory { FURNITURE, LIGHTING, KITCHEN, FABRIC, DECO, APPLIANCE, OTHER }

enum class ProductStatus { DRAFT, ON_SALE, SUSPENDED }

/**
 * 상품(카탈로그 단위). 판매 옵션 조합은 하위 [Sku]로 표현하며, 가격·재고는 SKU 단위로 관리된다.
 * 판매자(seller)가 소유하고, DRAFT로 등록된 뒤 ON_SALE로 노출된다.
 */
@Entity
@Table(name = "products", indexes = [Index(name = "idx_product_seller_status", columnList = "seller_id, status")])
class Product(
    @Column(name = "seller_id", nullable = false)
    val sellerId: Long,

    @Column(nullable = false, length = 200)
    var name: String,

    @Column(length = 1000)
    var description: String? = null,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    val category: ProductCategory,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var status: ProductStatus = ProductStatus.DRAFT,
) : BaseEntity() {

    fun onSale() {
        if (status != ProductStatus.DRAFT && status != ProductStatus.SUSPENDED)
            throw BusinessException(ErrorCode.INVALID_STATE_TRANSITION, "DRAFT 또는 SUSPENDED 상태에서만 판매 개시할 수 있습니다")
        status = ProductStatus.ON_SALE
    }

    fun suspendSales() {
        if (status != ProductStatus.ON_SALE)
            throw BusinessException(ErrorCode.INVALID_STATE_TRANSITION, "ON_SALE 상태에서만 판매 중지할 수 있습니다")
        status = ProductStatus.SUSPENDED
    }
}
