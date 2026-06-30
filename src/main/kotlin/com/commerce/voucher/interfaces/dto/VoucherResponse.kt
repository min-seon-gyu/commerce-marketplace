package com.commerce.voucher.interfaces.dto

import com.commerce.voucher.domain.Voucher
import java.math.BigDecimal

data class VoucherResponse(
    val id: Long,
    val voucherCode: String,
    val faceValue: BigDecimal,
    val balance: BigDecimal,
    val status: String,
    val regionId: Long,
    val memberId: Long,
)  {
    companion object {
        fun from(v: Voucher) = VoucherResponse(v.id, v.voucherCode, v.faceValue, v.balance, v.status.name, v.regionId, v.memberId)
    }
}

data class RedemptionResult(
    val transactionId: Long,
    val remainingBalance: BigDecimal,
)
