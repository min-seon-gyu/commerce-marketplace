package com.commerce.voucher.domain.event

import com.commerce.common.domain.DomainEvent
import java.math.BigDecimal

class VoucherExpiredEvent(
    override val aggregateId: Long,
    val remainingBalance: BigDecimal,
) : DomainEvent() {
    override val aggregateType = "VOUCHER"
    override val eventType = "VOUCHER_EXPIRED"
}
