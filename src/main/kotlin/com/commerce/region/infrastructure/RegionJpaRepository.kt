package com.commerce.region.infrastructure

import com.commerce.region.domain.Region
import org.springframework.data.jpa.repository.JpaRepository

interface RegionJpaRepository : JpaRepository<Region, Long> {
    fun findByRegionCode(regionCode: String): Region?
}
