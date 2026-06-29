package com.commerce.ledger.application

import com.commerce.ledger.domain.AccountCode
import com.commerce.ledger.domain.LedgerEntrySide
import com.commerce.ledger.infrastructure.LedgerJpaRepository
import com.commerce.voucher.infrastructure.VoucherJpaRepository
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal

data class VerificationResult(
    val isBalanced: Boolean,
    val globalDebitTotal: BigDecimal,
    val globalCreditTotal: BigDecimal,
    val imbalancedVouchers: List<ImbalancedVoucher>,
)

data class ImbalancedVoucher(
    val voucherId: Long,
    val cachedBalance: BigDecimal,
    val ledgerBalance: BigDecimal,
    val difference: BigDecimal,
)

@Service
class LedgerVerificationService(
    private val ledgerRepository: LedgerJpaRepository,
    private val voucherRepository: VoucherJpaRepository,
    private val meterRegistry: MeterRegistry,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(cron = "0 0 2 * * *")
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    fun scheduledVerification() {
        val result = verify()
        meterRegistry.gauge("ledger.verification.imbalance", result.imbalancedVouchers.size.toDouble())
        if (!result.isBalanced) {
            log.error("LEDGER IMBALANCE DETECTED: {} vouchers, global debit={}, credit={}",
                result.imbalancedVouchers.size, result.globalDebitTotal, result.globalCreditTotal)
        } else {
            log.info("Ledger verification passed. Global balance: {}", result.globalDebitTotal)
        }
    }

    fun verify(): VerificationResult {
        val globalDebit = ledgerRepository.sumBySide(LedgerEntrySide.DEBIT)
        val globalCredit = ledgerRepository.sumBySide(LedgerEntrySide.CREDIT)
        val globalBalanced = globalDebit.compareTo(globalCredit) == 0

        val imbalanced = checkVoucherBalances()

        return VerificationResult(
            isBalanced = globalBalanced && imbalanced.isEmpty(),
            globalDebitTotal = globalDebit,
            globalCreditTotal = globalCredit,
            imbalancedVouchers = imbalanced,
        )
    }

    private fun checkVoucherBalances(): List<ImbalancedVoucher> {
        val vouchers = voucherRepository.findAll()
        return vouchers.mapNotNull { voucher ->
            // VOUCHER_BALANCE 계정의 net balance = debit(발행,취소복원) - credit(사용,환불,만료)
            val ledgerBalance = ledgerRepository.netBalanceByVoucherAndAccount(
                voucher.id, AccountCode.VOUCHER_BALANCE
            )
            val cachedBalance = voucher.balance
            if (cachedBalance.compareTo(ledgerBalance) != 0) {
                ImbalancedVoucher(
                    voucherId = voucher.id,
                    cachedBalance = cachedBalance,
                    ledgerBalance = ledgerBalance,
                    difference = cachedBalance - ledgerBalance,
                )
            } else null
        }
    }
}
