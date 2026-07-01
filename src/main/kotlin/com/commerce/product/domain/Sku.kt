package com.commerce.product.domain

import com.commerce.common.domain.BaseEntity
import jakarta.persistence.*
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.math.BigDecimal

/**
 * SKU(Stock Keeping Unit) — 상품의 옵션 조합별 판매/재고 단위.
 * 장바구니 담기·주문 라인·재고 차감이 모두 SKU 단위로 이뤄진다.
 * options는 옵션명→값(JSON, 예: {"색상":"블랙","사이즈":"L"}), optionName은 표시용 요약이다.
 */
@Entity
@Table(
    name = "skus",
    uniqueConstraints = [UniqueConstraint(name = "uk_sku_code", columnNames = ["skuCode"])],
    indexes = [Index(name = "idx_sku_product", columnList = "product_id")],
)
class Sku(
    @Column(name = "product_id", nullable = false)
    val productId: Long,

    @Column(nullable = false, length = 40)
    val skuCode: String,

    @Column(nullable = false, length = 200)
    var optionName: String,

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "json", nullable = false)
    val options: String,

    @Column(nullable = false)
    var price: BigDecimal,
) : BaseEntity()
