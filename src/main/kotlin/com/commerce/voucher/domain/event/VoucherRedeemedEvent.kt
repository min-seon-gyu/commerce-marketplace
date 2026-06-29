package com.commerce.voucher.domain.event

import com.commerce.common.domain.DomainEvent
import java.math.BigDecimal

class VoucherRedeemedEvent(
    override val aggregateId: Long,
    val merchantId: Long,
    val amount: BigDecimal,
    val remainingBalance: BigDecimal,
    val transactionId: Long,
    val previousBalance: BigDecimal? = null,
) : DomainEvent() {
    override val aggregateType = "VOUCHER"
    override val eventType = "VOUCHER_REDEEMED"
    override val previousState: String?
        get() = previousBalance?.let { """{"balance":$it}""" }
    override val currentState: String
        get() = """{"balance":$remainingBalance,"amount":$amount,"merchantId":$merchantId}"""
}
