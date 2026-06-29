package com.commerce.point.interfaces.dto

import com.commerce.point.domain.PointAccount
import com.commerce.point.domain.PointTransaction
import java.math.BigDecimal

data class PointBalanceResponse(
    val memberId: Long,
    val balance: BigDecimal,
    val history: List<PointTransactionResponse>,
) {
    companion object {
        fun of(account: PointAccount, history: List<PointTransaction>) = PointBalanceResponse(
            memberId = account.memberId,
            balance = account.balance,
            history = history.map { PointTransactionResponse.from(it) },
        )
    }
}

data class PointTransactionResponse(
    val id: Long,
    val type: String,
    val amount: BigDecimal,
    val balanceAfter: BigDecimal,
    val sourceTransactionId: Long,
    val createdAt: String,
) {
    companion object {
        fun from(t: PointTransaction) = PointTransactionResponse(
            id = t.id,
            type = t.type.name,
            amount = t.amount,
            balanceAfter = t.balanceAfter,
            sourceTransactionId = t.sourceTransactionId,
            createdAt = t.createdAt.toString(),
        )
    }
}
