package com.commerce.ledger.application

import com.commerce.ledger.domain.*
import com.commerce.ledger.infrastructure.LedgerJpaRepository
import org.springframework.stereotype.Service
import java.math.BigDecimal

@Service
class LedgerService(
    private val ledgerRepository: LedgerJpaRepository
) {

    /**
     * 복식부기: debit 1행 + credit 1행 = 2행을 동일 트랜잭션에서 생성.
     * 이 메서드는 반드시 @Transactional 내에서 동기 호출해야 합니다.
     */
    fun record(
        debitAccount: AccountCode,
        creditAccount: AccountCode,
        amount: BigDecimal,
        transactionId: Long,
        entryType: LedgerEntryType
    ): List<LedgerEntry> {
        val debitEntry = LedgerEntry(
            account = debitAccount,
            side = LedgerEntrySide.DEBIT,
            amount = amount,
            transactionId = transactionId,
            entryType = entryType
        )
        val creditEntry = LedgerEntry(
            account = creditAccount,
            side = LedgerEntrySide.CREDIT,
            amount = amount,
            transactionId = transactionId,
            entryType = entryType
        )
        return ledgerRepository.saveAll(listOf(debitEntry, creditEntry))
    }

    fun getEntriesByTransactionId(transactionId: Long): List<LedgerEntry> =
        ledgerRepository.findByTransactionId(transactionId)

    fun netBalanceByAccount(account: AccountCode): BigDecimal {
        val debits = ledgerRepository.sumByAccountAndSide(account, LedgerEntrySide.DEBIT)
        val credits = ledgerRepository.sumByAccountAndSide(account, LedgerEntrySide.CREDIT)
        return debits - credits
    }

    fun globalDebitTotal(): BigDecimal = ledgerRepository.sumBySide(LedgerEntrySide.DEBIT)
    fun globalCreditTotal(): BigDecimal = ledgerRepository.sumBySide(LedgerEntrySide.CREDIT)
}
