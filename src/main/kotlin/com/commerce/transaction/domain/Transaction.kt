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
        Index(name = "idx_tx_seller_period", columnList = "sellerId, status, createdAt"),
    ]
)
class Transaction(
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    val type: TransactionType,

    @Column(nullable = false, precision = 15, scale = 2)
    val amount: BigDecimal,

    val sellerId: Long? = null,
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
}
