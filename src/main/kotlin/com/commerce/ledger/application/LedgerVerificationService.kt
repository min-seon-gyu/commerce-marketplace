package com.commerce.ledger.application

import com.commerce.ledger.domain.AccountCode
import com.commerce.ledger.domain.LedgerEntrySide
import com.commerce.ledger.infrastructure.LedgerJpaRepository
import com.commerce.point.infrastructure.PointAccountJpaRepository
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
    val pointBalanceMatches: Boolean,
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
    private val pointAccountRepository: PointAccountJpaRepository,
    private val meterRegistry: MeterRegistry,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(cron = "0 0 2 * * *")
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    fun scheduledVerification() {
        val result = verify()
        meterRegistry.gauge("ledger.verification.imbalance", result.imbalancedVouchers.size.toDouble())
        meterRegistry.gauge("ledger.verification.point.matches", if (result.pointBalanceMatches) 1.0 else 0.0)
        if (!result.isBalanced) {
            log.error(
                "LEDGER IMBALANCE DETECTED: {} vouchers, pointBalanceMatches={}, global debit={}, credit={}",
                result.imbalancedVouchers.size, result.pointBalanceMatches,
                result.globalDebitTotal, result.globalCreditTotal,
            )
        } else {
            log.info("Ledger verification passed. Global balance: {}", result.globalDebitTotal)
        }
    }

    fun verify(): VerificationResult {
        val globalDebit = ledgerRepository.sumBySide(LedgerEntrySide.DEBIT)
        val globalCredit = ledgerRepository.sumBySide(LedgerEntrySide.CREDIT)
        val globalBalanced = globalDebit.compareTo(globalCredit) == 0

        val imbalanced = checkVoucherBalances()

        val pointBalanceMatches = checkPointBalance()

        return VerificationResult(
            isBalanced = globalBalanced && imbalanced.isEmpty() && pointBalanceMatches,
            globalDebitTotal = globalDebit,
            globalCreditTotal = globalCredit,
            imbalancedVouchers = imbalanced,
            pointBalanceMatches = pointBalanceMatches,
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

    // POINT_BALANCE 차변정상: 원장 net(차변-대변) == 모든 PointAccount.balance 합.
    private fun checkPointBalance(): Boolean {
        val ledgerPointBalance =
            ledgerRepository.sumByAccountAndSide(AccountCode.POINT_BALANCE, LedgerEntrySide.DEBIT) -
            ledgerRepository.sumByAccountAndSide(AccountCode.POINT_BALANCE, LedgerEntrySide.CREDIT)
        val cachedPointTotal = pointAccountRepository.sumAllBalances()
        return cachedPointTotal.compareTo(ledgerPointBalance) == 0
    }
}
