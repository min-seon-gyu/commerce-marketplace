package com.commerce.product.infrastructure

import com.commerce.product.domain.Sku
import org.springframework.data.jpa.repository.JpaRepository

interface SkuJpaRepository : JpaRepository<Sku, Long> {
    fun findByProductId(productId: Long): List<Sku>
    fun existsBySkuCode(skuCode: String): Boolean
}
