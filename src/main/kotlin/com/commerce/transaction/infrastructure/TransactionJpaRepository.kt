package com.commerce.transaction.infrastructure

import com.commerce.transaction.domain.Transaction
import com.commerce.transaction.domain.TransactionStatus
import com.commerce.transaction.domain.TransactionType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import java.math.BigDecimal
import java.time.LocalDateTime

interface TransactionJpaRepository : JpaRepository<Transaction, Long> {

    fun countByVoucherIdAndStatus(voucherId: Long, status: TransactionStatus): Long

    @Query("""
        SELECT COALESCE(SUM(t.amount), 0) FROM Transaction t
        WHERE t.sellerId = :sellerId
        AND t.type = :type
        AND t.status = :status
        AND t.createdAt BETWEEN :start AND :end
    """)
    fun sumAmountBySellerAndTypeAndPeriod(
        sellerId: Long,
        type: TransactionType,
        status: TransactionStatus,
        start: LocalDateTime,
        end: LocalDateTime,
    ): BigDecimal
}
