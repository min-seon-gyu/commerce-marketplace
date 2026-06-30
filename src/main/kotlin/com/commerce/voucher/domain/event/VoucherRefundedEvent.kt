package com.commerce.voucher.domain.event

import com.commerce.common.domain.DomainEvent
import java.math.BigDecimal

class VoucherRefundedEvent(
    override val aggregateId: Long,
    val memberId: Long,
    val refundAmount: BigDecimal,
    val transactionId: Long,
) : DomainEvent() {
    override val aggregateType = "VOUCHER"
    override val eventType = "VOUCHER_REFUNDED"
}
