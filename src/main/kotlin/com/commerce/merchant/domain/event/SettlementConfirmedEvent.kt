package com.commerce.merchant.domain.event

import com.commerce.common.domain.DomainEvent
import java.math.BigDecimal
import java.time.LocalDate

class SettlementConfirmedEvent(
    override val aggregateId: Long,
    val merchantId: Long,
    val totalAmount: BigDecimal,
    val periodStart: LocalDate,
    val periodEnd: LocalDate,
) : DomainEvent() {
    override val aggregateType = "SETTLEMENT"
    override val eventType = "SETTLEMENT_CONFIRMED"
}
