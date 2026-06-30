package com.commerce.ledger.application

import com.commerce.ledger.domain.AccountCode
import com.commerce.ledger.domain.LedgerEntrySide
import com.commerce.ledger.domain.LedgerEntryType
import com.commerce.ledger.infrastructure.LedgerJpaRepository
import com.commerce.support.IntegrationTestSupport
import com.commerce.transaction.application.TransactionService
import com.commerce.transaction.domain.TransactionType
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal

@Transactional
class LedgerServiceTest : IntegrationTestSupport() {

    @Autowired lateinit var ledgerService: LedgerService
    @Autowired lateinit var ledgerRepository: LedgerJpaRepository
    @Autowired lateinit var transactionService: TransactionService

    @Test
    fun `record should create debit and credit entry pair (2 rows)`() {
        val tx = transactionService.create(TransactionType.PURCHASE, BigDecimal("50000"))

        ledgerService.record(
            debitAccount = AccountCode.VOUCHER_BALANCE,
            creditAccount = AccountCode.MEMBER_CASH,
            amount = BigDecimal("50000"),
            transactionId = tx.id,
            entryType = LedgerEntryType.PURCHASE
        )

        val entries = ledgerRepository.findByTransactionId(tx.id)
        entries.size shouldBe 2

        val debit = entries.first { it.side == LedgerEntrySide.DEBIT }
        val credit = entries.first { it.side == LedgerEntrySide.CREDIT }
        debit.account shouldBe AccountCode.VOUCHER_BALANCE
        debit.amount.compareTo(BigDecimal("50000")) shouldBe 0
        credit.account shouldBe AccountCode.MEMBER_CASH
        credit.amount.compareTo(BigDecimal("50000")) shouldBe 0
    }

    @Test
    fun `global debit and credit totals should be equal after balanced entries`() {
        val tx = transactionService.create(TransactionType.PURCHASE, BigDecimal("30000"))
        ledgerService.record(
            AccountCode.VOUCHER_BALANCE, AccountCode.MEMBER_CASH,
            BigDecimal("30000"), tx.id, LedgerEntryType.PURCHASE
        )

        val debitTotal = ledgerService.globalDebitTotal()
        val creditTotal = ledgerService.globalCreditTotal()
        debitTotal.compareTo(creditTotal) shouldBe 0
    }
}
