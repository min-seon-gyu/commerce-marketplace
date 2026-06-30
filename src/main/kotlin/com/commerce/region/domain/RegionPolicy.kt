package com.commerce.region.domain

import jakarta.persistence.Column
import jakarta.persistence.Embeddable
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import java.math.BigDecimal

@Embeddable
data class RegionPolicy(
    @Column(nullable = false, precision = 5, scale = 2)
    val discountRate: BigDecimal,

    @Column(nullable = false, precision = 15, scale = 2)
    val purchaseLimitPerPerson: BigDecimal,

    @Column(nullable = false, precision = 15, scale = 2)
    val monthlyIssuanceLimit: BigDecimal,

    @Column(nullable = false, precision = 3, scale = 2)
    val refundThresholdRatio: BigDecimal,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    val settlementPeriod: SettlementPeriod
)
