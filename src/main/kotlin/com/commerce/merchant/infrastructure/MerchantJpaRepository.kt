package com.commerce.merchant.infrastructure

import com.commerce.merchant.domain.Merchant
import com.commerce.merchant.domain.MerchantStatus
import org.springframework.data.jpa.repository.JpaRepository

interface MerchantJpaRepository : JpaRepository<Merchant, Long> {
    fun findByRegionIdAndStatus(regionId: Long, status: MerchantStatus): List<Merchant>
}
