package com.commerce.voucher.application

import com.commerce.common.exception.BusinessException
import com.commerce.common.exception.ErrorCode
import com.commerce.ledger.application.LedgerService
import com.commerce.ledger.domain.AccountCode
import com.commerce.ledger.domain.LedgerEntryType
import com.commerce.transaction.application.TransactionService
import com.commerce.transaction.domain.TransactionType
import com.commerce.voucher.domain.Voucher
import com.commerce.voucher.domain.event.VoucherWithdrawnEvent
import com.commerce.voucher.infrastructure.VoucherJpaRepository
import com.commerce.voucher.infrastructure.VoucherLockManager
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate

@Service
class VoucherWithdrawalService(
    private val voucherRepository: VoucherJpaRepository,
    private val lockManager: VoucherLockManager,
    private val ledgerService: LedgerService,
    private val transactionService: TransactionService,
    private val eventPublisher: ApplicationEventPublisher,
    private val transactionTemplate: TransactionTemplate,
) {

    /**
     * 청약철회.
     * 분산락 → 트랜잭션(커밋) → 분산락 해제 순서 보장.
     */
    fun withdraw(voucherId: Long, memberId: Long): Voucher {
        return lockManager.withVoucherLock(voucherId) {
            transactionTemplate.execute { _ ->
                val voucher = voucherRepository.findByIdForUpdate(voucherId)
                    ?: throw BusinessException(ErrorCode.ENTITY_NOT_FOUND)

                if (voucher.memberId != memberId)
                    throw BusinessException(ErrorCode.INVALID_INPUT, "본인의 상품권만 철회할 수 있습니다")

                voucher.requestWithdrawal()
                val refundAmount = voucher.completeWithdrawal()

                val tx = transactionService.create(
                    type = TransactionType.WITHDRAWAL,
                    amount = refundAmount,
                    voucherId = voucherId,
                    memberId = memberId,
                )
                ledgerService.record(
                    debitAccount = AccountCode.REFUND_PAYABLE,
                    creditAccount = AccountCode.VOUCHER_BALANCE,
                    amount = refundAmount,
                    transactionId = tx.id,
                    entryType = LedgerEntryType.WITHDRAWAL,
                )
                tx.complete()

                eventPublisher.publishEvent(
                    VoucherWithdrawnEvent(voucherId, memberId, refundAmount, tx.id)
                )

                voucher
            }!!
        }
    }
}
