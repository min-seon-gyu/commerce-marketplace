package com.commerce.transaction.interfaces

import com.commerce.common.api.ApiResponse
import com.commerce.common.idempotency.Idempotent
import com.commerce.transaction.application.TransactionCancelService
import com.commerce.transaction.application.TransactionService
import com.commerce.transaction.domain.Transaction
import org.springframework.web.bind.annotation.*
import java.math.BigDecimal

data class TransactionResponse(
    val id: Long,
    val type: String,
    val amount: BigDecimal,
    val status: String,
    val voucherId: Long?,
    val sellerId: Long?,
    val originalTransactionId: Long?,
) {
    companion object {
        fun from(t: Transaction) = TransactionResponse(
            id = t.id,
            type = t.type.name,
            amount = t.amount,
            status = t.status.name,
            voucherId = t.voucherId,
            sellerId = t.sellerId,
            originalTransactionId = t.originalTransactionId,
        )
    }
}

data class CancelResponse(
    val originalTransactionId: Long,
    val compensatingTransactionId: Long,
)

@RestController
@RequestMapping("/api/v1/transactions")
class TransactionController(
    private val transactionService: TransactionService,
    private val cancelService: TransactionCancelService,
) {

    @GetMapping("/{id}")
    fun getById(@PathVariable id: Long): ApiResponse<TransactionResponse> =
        ApiResponse.ok(TransactionResponse.from(transactionService.getById(id)))

    @PostMapping("/{id}/cancel")
    @Idempotent
    fun cancel(@PathVariable id: Long): ApiResponse<CancelResponse> {
        val compensatingId = cancelService.cancel(id)
        return ApiResponse.ok(CancelResponse(
            originalTransactionId = id,
            compensatingTransactionId = compensatingId,
        ))
    }
}
