package com.commerce.ledger.infrastructure

import com.commerce.ledger.domain.AccountCode
import com.commerce.ledger.domain.LedgerEntry
import com.commerce.ledger.domain.LedgerEntrySide
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import java.math.BigDecimal

interface LedgerJpaRepository : JpaRepository<LedgerEntry, Long> {

    fun findByTransactionId(transactionId: Long): List<LedgerEntry>

    @Query("SELECT COALESCE(SUM(e.amount), 0) FROM LedgerEntry e WHERE e.account = :account AND e.side = :side")
    fun sumByAccountAndSide(account: AccountCode, side: LedgerEntrySide): BigDecimal

    @Query("SELECT COALESCE(SUM(e.amount), 0) FROM LedgerEntry e WHERE e.side = :side")
    fun sumBySide(side: LedgerEntrySide): BigDecimal
}
