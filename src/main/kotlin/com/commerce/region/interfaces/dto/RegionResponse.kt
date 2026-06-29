package com.commerce.region.interfaces.dto

import com.commerce.region.domain.Region
import java.math.BigDecimal

data class RegionResponse(
    val id: Long,
    val name: String,
    val regionCode: String,
    val status: String,
    val discountRate: BigDecimal,
    val purchaseLimitPerPerson: BigDecimal,
    val monthlyIssuanceLimit: BigDecimal,
    val refundThresholdRatio: BigDecimal,
    val settlementPeriod: String
) {
    companion object {
        fun from(region: Region) = RegionResponse(
            id = region.id,
            name = region.name,
            regionCode = region.regionCode,
            status = region.status.name,
            discountRate = region.policy.discountRate,
            purchaseLimitPerPerson = region.policy.purchaseLimitPerPerson,
            monthlyIssuanceLimit = region.policy.monthlyIssuanceLimit,
            refundThresholdRatio = region.policy.refundThresholdRatio,
            settlementPeriod = region.policy.settlementPeriod.name
        )
    }
}
