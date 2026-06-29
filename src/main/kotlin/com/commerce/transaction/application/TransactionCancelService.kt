package com.commerce.transaction.application

import com.commerce.common.exception.BusinessException
import com.commerce.common.exception.ErrorCode
import com.commerce.ledger.application.LedgerService
import com.commerce.ledger.domain.AccountCode
import com.commerce.ledger.domain.LedgerEntryType
import com.commerce.transaction.domain.TransactionType
import com.commerce.transaction.domain.event.TransactionCancelledEvent
import com.commerce.transaction.infrastructure.TransactionJpaRepository
import com.commerce.voucher.infrastructure.VoucherJpaRepository
import com.commerce.voucher.infrastructure.VoucherLockManager
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate

@Service
class TransactionCancelService(
    private val transactionRepository: TransactionJpaRepository,
    private val transactionService: TransactionService,
    private val voucherRepository: VoucherJpaRepository,
    private val lockManager: VoucherLockManager,
    private val ledgerService: LedgerService,
    private val eventPublisher: ApplicationEventPublisher,
    private val transactionTemplate: TransactionTemplate,
) {

    /**
     * 거래 취소 (보상 트랜잭션).
     * 분산락 → 트랜잭션(커밋) → 분산락 해제 순서 보장.
     * requestCancel()도 트랜잭션 + 락 내부에서 실행하여 상태 변경 일관성 확보.
     */
    fun cancel(transactionId: Long): Long {
        // 락 키(voucherId)만 얻기 위한 사전 조회 — 상태 변경은 트랜잭션 내부에서 수행
        val voucherId = transactionService.getById(transactionId).voucherId
            ?: throw BusinessException(ErrorCode.INVALID_INPUT, "상품권 거래만 취소할 수 있습니다")

        return lockManager.withVoucherLock(voucherId) {
            transactionTemplate.execute { _ ->
                // 트랜잭션 내부에서 다시 로드해 managed 상태로 만든다.
                // (트랜잭션 밖에서 로드한 detached 엔티티는 open-in-view=false 환경에서
                //  상태 변경(CANCELLED)이 영속화되지 않아 정산 집계에서 취소가 누락됨)
                val original = transactionRepository.findById(transactionId)
                    .orElseThrow { BusinessException(ErrorCode.ENTITY_NOT_FOUND) }

                original.requestCancel()

                // Create compensating transaction
                val compensating = transactionService.create(
                    type = TransactionType.CANCELLATION,
                    amount = original.amount,
                    voucherId = voucherId,
                    merchantId = original.merchantId,
                    originalTransactionId = original.id,
                )

                // Reverse ledger entries (debit VOUCHER_BALANCE, credit MERCHANT_RECEIVABLE)
                ledgerService.record(
                    debitAccount = AccountCode.VOUCHER_BALANCE,
                    creditAccount = AccountCode.MERCHANT_RECEIVABLE,
                    amount = original.amount,
                    transactionId = compensating.id,
                    entryType = LedgerEntryType.CANCELLATION,
                )
                compensating.complete()
                original.cancel()

                // Restore voucher balance
                val voucher = voucherRepository.findByIdForUpdate(voucherId)
                    ?: throw BusinessException(ErrorCode.ENTITY_NOT_FOUND)
                voucher.restoreBalance(original.amount)

                eventPublisher.publishEvent(
                    TransactionCancelledEvent(original.id, voucherId, original.amount)
                )

                compensating.id
            }!!
        }
    }
}
