package com.commerce.transaction.domain.event

import com.commerce.common.domain.DomainEvent
import java.math.BigDecimal

class TransactionCancelledEvent(
    override val aggregateId: Long,
    val voucherId: Long?,
    val cancelAmount: BigDecimal,
) : DomainEvent() {
    override val aggregateType = "TRANSACTION"
    override val eventType = "TRANSACTION_CANCELLED"
}
