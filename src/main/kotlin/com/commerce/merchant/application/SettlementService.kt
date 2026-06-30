package com.commerce.merchant.application

import com.commerce.common.exception.BusinessException
import com.commerce.common.exception.ErrorCode
import com.commerce.ledger.application.LedgerService
import com.commerce.ledger.domain.AccountCode
import com.commerce.ledger.domain.LedgerEntryType
import com.commerce.merchant.domain.Settlement
import com.commerce.merchant.domain.event.SettlementConfirmedEvent
import com.commerce.merchant.infrastructure.SettlementJpaRepository
import com.commerce.transaction.application.TransactionService
import com.commerce.transaction.domain.TransactionStatus
import com.commerce.transaction.domain.TransactionType
import com.commerce.transaction.infrastructure.TransactionJpaRepository
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate
import java.time.LocalTime

@Service
@Transactional(readOnly = true)
class SettlementService(
    private val settlementRepository: SettlementJpaRepository,
    private val transactionRepository: TransactionJpaRepository,
    private val transactionService: TransactionService,
    private val ledgerService: LedgerService,
    private val eventPublisher: ApplicationEventPublisher,
) {

    @Transactional
    fun calculate(merchantId: Long, periodStart: LocalDate, periodEnd: LocalDate): Settlement {
        // Check duplicate
        settlementRepository.findByMerchantIdAndPeriodStartAndPeriodEnd(merchantId, periodStart, periodEnd)
            ?.let { throw BusinessException(ErrorCode.INVALID_INPUT, "이미 해당 기간 정산이 존재합니다") }

        val start = periodStart.atStartOfDay()
        val end = periodEnd.atTime(LocalTime.MAX)

        // COMPLETED 상태인 결제만 합산 (취소된 원 거래는 CANCELLED 상태이므로 자동 제외)
        val totalAmount = transactionRepository.sumAmountByMerchantAndTypeAndPeriod(
            merchantId, TransactionType.REDEMPTION, TransactionStatus.COMPLETED, start, end
        )

        return settlementRepository.save(
            Settlement(
                merchantId = merchantId,
                periodStart = periodStart,
                periodEnd = periodEnd,
                totalAmount = totalAmount,
            )
        )
    }

    @Transactional
    fun confirm(settlementId: Long): Settlement {
        val settlement = getById(settlementId)
        settlement.confirm()

        // 정산 확정 시 원장 기록: 가맹점 미수금 → 정산 미지급금
        val tx = transactionService.create(
            type = TransactionType.SETTLEMENT,
            amount = settlement.totalAmount,
            merchantId = settlement.merchantId,
        )
        ledgerService.record(
            debitAccount = AccountCode.SETTLEMENT_PAYABLE,
            creditAccount = AccountCode.MERCHANT_RECEIVABLE,
            amount = settlement.totalAmount,
            transactionId = tx.id,
            entryType = LedgerEntryType.SETTLEMENT,
        )
        tx.complete()

        eventPublisher.publishEvent(
            SettlementConfirmedEvent(
                aggregateId = settlement.id,
                merchantId = settlement.merchantId,
                totalAmount = settlement.totalAmount,
                periodStart = settlement.periodStart,
                periodEnd = settlement.periodEnd,
            )
        )
        return settlement
    }

    @Transactional
    fun dispute(settlementId: Long, reason: String): Settlement {
        val settlement = getById(settlementId)
        settlement.dispute(reason)
        return settlement
    }

    fun getById(id: Long): Settlement =
        settlementRepository.findById(id)
            .orElseThrow { BusinessException(ErrorCode.ENTITY_NOT_FOUND) }
}
