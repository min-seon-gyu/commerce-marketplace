package com.commerce.voucher.interfaces.dto

import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotNull
import java.math.BigDecimal

data class PurchaseVoucherRequest(
    @field:NotNull val memberId: Long,
    @field:NotNull val regionId: Long,
    @field:NotNull @field:Min(1000) val faceValue: BigDecimal,
)

data class RedeemRequest(
    @field:NotNull val merchantId: Long,
    @field:NotNull @field:Min(1) val amount: BigDecimal,
)

data class RefundRequest(
    @field:NotNull val memberId: Long,
)

data class WithdrawRequest(
    @field:NotNull val memberId: Long,
)
