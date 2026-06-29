package com.commerce.transaction.domain

import com.commerce.common.domain.BaseEntity
import com.commerce.common.exception.BusinessException
import com.commerce.common.exception.ErrorCode
import jakarta.persistence.*
import java.math.BigDecimal

@Entity
@Table(
    name = "transactions",
    indexes = [
        Index(name = "idx_tx_voucher", columnList = "voucherId, createdAt"),
        Index(name = "idx_tx_merchant_period", columnList = "merchantId, status, createdAt"),
    ]
)
class Transaction(
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    val type: TransactionType,

    @Column(nullable = false)
    val amount: BigDecimal,

    val voucherId: Long? = null,
    val merchantId: Long? = null,
    val memberId: Long? = null,
    val originalTransactionId: Long? = null,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var status: TransactionStatus = TransactionStatus.PENDING,
) : BaseEntity() {

    init {
        require(amount > BigDecimal.ZERO) { "거래 금액은 0보다 커야 합니다" }
    }

    fun complete() {
        if (status != TransactionStatus.PENDING)
            throw BusinessException(ErrorCode.INVALID_STATE_TRANSITION)
        status = TransactionStatus.COMPLETED
    }

    fun fail() {
        if (status != TransactionStatus.PENDING)
            throw BusinessException(ErrorCode.INVALID_STATE_TRANSITION)
        status = TransactionStatus.FAILED
    }

    fun requestCancel() {
        if (status != TransactionStatus.COMPLETED)
            throw BusinessException(ErrorCode.TRANSACTION_NOT_CANCELLABLE)
        status = TransactionStatus.CANCEL_REQUESTED
    }

    fun cancel() {
        if (status != TransactionStatus.CANCEL_REQUESTED)
            throw BusinessException(ErrorCode.INVALID_STATE_TRANSITION)
        status = TransactionStatus.CANCELLED
    }
}
