package com.commerce.region.interfaces.dto

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Positive
import java.math.BigDecimal

data class CreateRegionRequest(
    @field:NotBlank val name: String,
    @field:NotBlank val regionCode: String,
    @field:Positive val discountRate: BigDecimal,
    @field:Positive val purchaseLimitPerPerson: BigDecimal,
    @field:Positive val monthlyIssuanceLimit: BigDecimal,
    val refundThresholdRatio: BigDecimal = BigDecimal("0.60"),
    val settlementPeriod: String = "MONTHLY"
)
