package com.commerce.product.infrastructure

import com.commerce.product.domain.Product
import com.commerce.product.domain.ProductStatus
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository

interface ProductJpaRepository : JpaRepository<Product, Long> {
    fun findByStatus(status: ProductStatus, pageable: Pageable): Page<Product>
    fun findBySellerId(sellerId: Long): List<Product>
}
