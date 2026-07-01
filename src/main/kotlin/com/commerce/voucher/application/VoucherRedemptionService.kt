package com.commerce.voucher.application

import com.commerce.common.exception.BusinessException
import com.commerce.common.exception.ErrorCode
import com.commerce.ledger.application.LedgerService
import com.commerce.ledger.domain.AccountCode
import com.commerce.ledger.domain.LedgerEntryType
import com.commerce.point.application.PointEarnService
import com.commerce.transaction.application.TransactionService
import com.commerce.transaction.domain.TransactionType
import com.commerce.voucher.domain.event.VoucherRedeemedEvent
import com.commerce.voucher.infrastructure.VoucherJpaRepository
import com.commerce.voucher.infrastructure.VoucherLockManager
import com.commerce.voucher.interfaces.dto.RedemptionResult
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import java.math.BigDecimal

@Service
class VoucherRedemptionService(
    private val voucherRepository: VoucherJpaRepository,
    private val lockManager: VoucherLockManager,
    private val ledgerService: LedgerService,
    private val transactionService: TransactionService,
    private val eventPublisher: ApplicationEventPublisher,
    private val meterRegistry: MeterRegistry,
    private val pointEarnService: PointEarnService,
    private val transactionTemplate: TransactionTemplate,
) {

    /**
     * 상품권 결제(사용).
     * 분산락 → 트랜잭션(커밋) → 분산락 해제 순서를 보장하여
     * 락 해제 후 다른 스레드가 커밋 전 데이터를 읽는 문제를 방지한다.
     */
    fun redeem(voucherId: Long, sellerId: Long, amount: BigDecimal): RedemptionResult {
        return lockManager.withVoucherLock(voucherId) {
            val timer = Timer.start(meterRegistry)
            try {
                val result = transactionTemplate.execute { _ ->
                    val voucher = voucherRepository.findByIdForUpdate(voucherId)
                        ?: throw BusinessException(ErrorCode.ENTITY_NOT_FOUND)

                    if (!voucher.isUsable()) throw BusinessException(ErrorCode.VOUCHER_NOT_USABLE)
                    if (voucher.isExpired()) throw BusinessException(ErrorCode.VOUCHER_EXPIRED)
                    if (voucher.balance < amount) throw BusinessException(ErrorCode.INSUFFICIENT_BALANCE)

                    val previousBalance = voucher.balance
                    voucher.redeem(amount)

                    val tx = transactionService.create(
                        type = TransactionType.REDEMPTION,
                        amount = amount,
                        voucherId = voucherId,
                        sellerId = sellerId,
                    )
                    ledgerService.record(
                        debitAccount = AccountCode.MERCHANT_RECEIVABLE,
                        creditAccount = AccountCode.VOUCHER_BALANCE,
                        amount = amount,
                        transactionId = tx.id,
                        entryType = LedgerEntryType.REDEMPTION,
                    )
                    tx.complete()

                    pointEarnService.earn(
                        memberId = voucher.memberId,
                        baseAmount = amount,
                        sourceTransactionId = tx.id,
                    )

                    eventPublisher.publishEvent(
                        VoucherRedeemedEvent(voucherId, sellerId, amount, voucher.balance, tx.id, previousBalance)
                    )

                    RedemptionResult(transactionId = tx.id, remainingBalance = voucher.balance)
                }!!

                meterRegistry.counter("voucher.redemption.count", "result", "success").increment()
                result
            } catch (e: Exception) {
                meterRegistry.counter("voucher.redemption.count", "result", "failure").increment()
                throw e
            } finally {
                timer.stop(meterRegistry.timer("voucher.redemption.duration"))
            }
        }
    }
}
