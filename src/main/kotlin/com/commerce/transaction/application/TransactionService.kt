package com.commerce.transaction.application

import com.commerce.common.exception.BusinessException
import com.commerce.common.exception.ErrorCode
import com.commerce.transaction.domain.Transaction
import com.commerce.transaction.domain.TransactionType
import com.commerce.transaction.infrastructure.TransactionJpaRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal

@Service
@Transactional(readOnly = true)
class TransactionService(
    private val transactionRepository: TransactionJpaRepository
) {

    @Transactional
    fun create(
        type: TransactionType,
        amount: BigDecimal,
        sellerId: Long? = null,
        memberId: Long? = null,
        originalTransactionId: Long? = null,
    ): Transaction {
        return transactionRepository.save(
            Transaction(
                type = type,
                amount = amount,
                sellerId = sellerId,
                memberId = memberId,
                originalTransactionId = originalTransactionId,
            )
        )
    }

    fun getById(id: Long): Transaction =
        transactionRepository.findById(id)
            .orElseThrow { BusinessException(ErrorCode.ENTITY_NOT_FOUND) }
}
