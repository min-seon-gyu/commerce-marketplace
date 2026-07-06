package com.commerce.transaction.interfaces

import com.commerce.common.api.ApiResponse
import com.commerce.common.exception.BusinessException
import com.commerce.common.exception.ErrorCode
import com.commerce.common.security.SecurityUtils
import com.commerce.transaction.application.TransactionService
import com.commerce.transaction.domain.Transaction
import org.springframework.web.bind.annotation.*
import java.math.BigDecimal

data class TransactionResponse(
    val id: Long,
    val type: String,
    val amount: BigDecimal,
    val status: String,
    val sellerId: Long?,
    val originalTransactionId: Long?,
) {
    companion object {
        fun from(t: Transaction) = TransactionResponse(
            id = t.id,
            type = t.type.name,
            amount = t.amount,
            status = t.status.name,
            sellerId = t.sellerId,
            originalTransactionId = t.originalTransactionId,
        )
    }
}

@RestController
@RequestMapping("/api/v1/transactions")
class TransactionController(
    private val transactionService: TransactionService,
) {

    /**
     * 결제 거래 단건 조회 — 본인(memberId) 거래만. ADMIN은 전체 조회 가능.
     * 판매자 정산 거래(memberId 없음)는 소유자 매칭이 되지 않아 ADMIN 전용이 된다.
     */
    @GetMapping("/{id}")
    fun getById(@PathVariable id: Long): ApiResponse<TransactionResponse> {
        val transaction = transactionService.getById(id)
        if (!SecurityUtils.isAdmin() && transaction.memberId != SecurityUtils.currentMemberId())
            throw BusinessException(ErrorCode.ACCESS_DENIED)
        return ApiResponse.ok(TransactionResponse.from(transaction))
    }
}
