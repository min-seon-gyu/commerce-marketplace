package com.commerce.seller.infrastructure

import com.commerce.seller.domain.Seller
import com.commerce.seller.domain.SellerStatus
import org.springframework.data.jpa.repository.JpaRepository

interface SellerJpaRepository : JpaRepository<Seller, Long> {
    fun findByRegionIdAndStatus(regionId: Long, status: SellerStatus): List<Seller>
}
