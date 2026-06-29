package com.commerce.voucher.application

import com.commerce.ledger.application.LedgerService
import com.commerce.ledger.domain.AccountCode
import com.commerce.ledger.domain.LedgerEntryType
import com.commerce.transaction.application.TransactionService
import com.commerce.transaction.domain.TransactionType
import com.commerce.voucher.domain.VoucherStatus
import com.commerce.voucher.domain.event.VoucherExpiredEvent
import com.commerce.voucher.infrastructure.VoucherJpaRepository
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.data.domain.PageRequest
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.LocalDateTime

@Component
class VoucherExpiryScheduler(
    private val voucherRepository: VoucherJpaRepository,
    private val expiryProcessor: VoucherExpiryProcessor,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(cron = "0 */5 * * * *")
    fun processExpiredVouchers() {
        val expiredIds = voucherRepository.findExpiredVoucherIds(
            statuses = listOf(VoucherStatus.ACTIVE, VoucherStatus.PARTIALLY_USED),
            now = LocalDateTime.now(),
            limit = PageRequest.of(0, 100),
        )
        expiredIds.forEach { id ->
            try {
                expiryProcessor.processExpiry(id)
            } catch (e: Exception) {
                log.error("Failed to expire voucher {}: {}", id, e.message)
            }
        }
    }
}

@Service
class VoucherExpiryProcessor(
    private val voucherRepository: VoucherJpaRepository,
    private val ledgerService: LedgerService,
    private val transactionService: TransactionService,
    private val eventPublisher: ApplicationEventPublisher,
) {

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun processExpiry(voucherId: Long) {
        val locked = voucherRepository.findByIdForUpdate(voucherId) ?: return
        if (!locked.isUsable()) return

        val remainingBalance = locked.balance
        locked.expire()
        locked.balance = BigDecimal.ZERO

        if (remainingBalance > BigDecimal.ZERO) {
            val tx = transactionService.create(
                type = TransactionType.EXPIRY,
                amount = remainingBalance,
                voucherId = locked.id,
            )
            ledgerService.record(
                debitAccount = AccountCode.EXPIRED_VOUCHER,
                creditAccount = AccountCode.VOUCHER_BALANCE,
                amount = remainingBalance,
                transactionId = tx.id,
                entryType = LedgerEntryType.EXPIRY,
            )
            tx.complete()
        }

        eventPublisher.publishEvent(VoucherExpiredEvent(locked.id, remainingBalance))
    }
}
