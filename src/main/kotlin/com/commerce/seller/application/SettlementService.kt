package com.commerce.seller.application

import com.commerce.common.exception.BusinessException
import com.commerce.common.exception.ErrorCode
import com.commerce.ledger.application.LedgerService
import com.commerce.ledger.domain.AccountCode
import com.commerce.ledger.domain.LedgerEntryType
import com.commerce.seller.domain.Settlement
import com.commerce.seller.domain.event.SettlementConfirmedEvent
import com.commerce.seller.infrastructure.SellerJpaRepository
import com.commerce.seller.infrastructure.SettlementJpaRepository
import com.commerce.order.infrastructure.OrderLineJpaRepository
import com.commerce.seller.domain.SettlementPeriod
import com.commerce.seller.domain.SettlementStatus
import com.commerce.transaction.application.TransactionService
import com.commerce.transaction.domain.TransactionType
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId

@Service
@Transactional(readOnly = true)
class SettlementService(
    private val settlementRepository: SettlementJpaRepository,
    private val orderLineRepository: OrderLineJpaRepository,
    private val transactionService: TransactionService,
    private val ledgerService: LedgerService,
    private val sellerRepository: SellerJpaRepository,
    private val eventPublisher: ApplicationEventPublisher,
) {

    /**
     * 판매자의 정산 주기(일/주/월)에 맞춰 KST 역월 기준 정산 기간을 산출한 뒤 정산한다.
     * 기준일(referenceDate)이 속한 주기 구간 [start, end]를 닫힌 구간으로 계산한다.
     *   - DAILY  : 기준일 당일
     *   - WEEKLY : 기준일이 속한 ISO 주(월~일)
     *   - MONTHLY: 기준일이 속한 달의 1일~말일
     */
    @Transactional
    fun calculateForPeriod(
        sellerId: Long,
        referenceDate: LocalDate = LocalDate.now(KST),
    ): Settlement {
        val seller = sellerRepository.findById(sellerId)
            .orElseThrow { BusinessException(ErrorCode.ENTITY_NOT_FOUND) }
        val (start, end) = resolvePeriod(seller.settlementPeriod, referenceDate)
        return calculate(sellerId, start, end)
    }

    /**
     * 결산 배치용: 기준일 기준 **가장 최근에 끝난** 정산 구간의 미저장 Settlement를 만든다
     * (중복 또는 순정산액 0 이하면 null=스킵). 진행 중인 주기는 대상이 아니다 — 정산은 주기 종료 후에만
     * 생성한다. 배치가 매일 돌므로 경계일 실행이 실패해도 다음 실행이 같은 주기를 다시 시도한다(자가 치유).
     * calculate()와 달리 예외를 던지지 않아 청크 처리에서 head-of-line 없이 다음 판매자으로 진행한다.
     * 자체 read-only tx 안에서 판매자를 로드해 정산주기를 읽는다.
     */
    @Transactional(readOnly = true)
    fun buildSettlementForBatch(sellerId: Long, referenceDate: LocalDate): Settlement? {
        val seller = sellerRepository.findById(sellerId).orElse(null) ?: return null
        // ref가 속한 주기가 아직 진행 중이면(periodEnd > ref) 직전 주기로 물러난다.
        val current = resolvePeriod(seller.settlementPeriod, referenceDate)
        val (start, end) = if (current.second > referenceDate) {
            resolvePeriod(seller.settlementPeriod, current.first.minusDays(1))
        } else current

        // 재실행 안전: 같은 구간 정산이 이미 있으면 스킵(unique 제약과 이중 방어).
        if (settlementRepository.findBySellerIdAndPeriodStartAndPeriodEnd(sellerId, start, end) != null) return null

        // 누적 순정산액(총 미환불 매출 − 확정정산 합). 0 이하면 만들지 않는다 — 당기 순정산 없음이거나
        // 확정 후 환불로 이월(carry)된 상태이며, 다음 기수에 매출이 이를 상쇄하면 양수로 잡힌다.
        val net = cumulativeNet(sellerId)
        if (net.compareTo(BigDecimal.ZERO) <= 0) return null

        return Settlement(
            sellerId = sellerId,
            periodStart = start,
            periodEnd = end,
            totalAmount = net,
        )
    }

    /**
     * 누적 정산액 = 판매자의 총 미환불 매출 − 이미 확정/지급(CONFIRMED,PAID)된 정산액 합.
     *
     * 정산 확정/지급 **이후** 발생한 환불도 총 미환불 매출이 줄어 다음 정산에서 자연히 차감된다(clawback) —
     * 판매자 과지급을 막는다. 순정산액이 음수면(환불 > 신규 매출) 정산을 만들지 않고 다음 기수로 이월되며,
     * 이후 매출이 이를 상쇄하면 그만큼만 지급된다. (기간 키/unique는 그대로 유지 — 멱등·중복 방지.)
     */
    private fun cumulativeNet(sellerId: Long): BigDecimal {
        val owed = orderLineRepository.sumSellerNonRefundedSalesAllTime(sellerId)
        val settled = settlementRepository.sumAmountBySellerAndStatusIn(
            sellerId, listOf(SettlementStatus.CONFIRMED, SettlementStatus.PAID),
        )
        return owed - settled
    }

    private fun resolvePeriod(period: SettlementPeriod, ref: LocalDate): Pair<LocalDate, LocalDate> = when (period) {
        SettlementPeriod.DAILY -> ref to ref
        SettlementPeriod.WEEKLY -> ref.with(DayOfWeek.MONDAY) to ref.with(DayOfWeek.SUNDAY)
        SettlementPeriod.MONTHLY -> ref.withDayOfMonth(1) to ref.withDayOfMonth(ref.lengthOfMonth())
    }

    @Transactional
    fun calculate(sellerId: Long, periodStart: LocalDate, periodEnd: LocalDate): Settlement {
        val seller = sellerRepository.findById(sellerId)
            .orElseThrow { BusinessException(ErrorCode.ENTITY_NOT_FOUND) }
        // 명시 기간도 판매자 정산주기 경계와 일치해야 한다 — 임의 구간(예: 월 중간까지 자른 3/1~3/12)으로
        // periodEnd를 과거로 만들어 주기 종료 확정 게이트를 우회하는 경로를 차단한다.
        if (resolvePeriod(seller.settlementPeriod, periodStart) != (periodStart to periodEnd))
            throw BusinessException(ErrorCode.SETTLEMENT_PERIOD_MISMATCH)

        // Check duplicate
        settlementRepository.findBySellerIdAndPeriodStartAndPeriodEnd(sellerId, periodStart, periodEnd)
            ?.let { throw BusinessException(ErrorCode.INVALID_INPUT, "이미 해당 기간 정산이 존재합니다") }

        // 누적 순정산액(총 미환불 매출 − 확정정산 합). 확정 이후 취소·환불이 자연 반영된다.
        return settlementRepository.save(
            Settlement(
                sellerId = sellerId,
                periodStart = periodStart,
                periodEnd = periodEnd,
                totalAmount = cumulativeNet(sellerId),
            )
        )
    }

    @Transactional
    fun confirm(settlementId: Long): Settlement {
        val settlement = getById(settlementId)
        // 확정 시점에 누적 순정산액을 재계산한다(스냅샷 신뢰 금지). 이 정산은 아직 CONFIRMED/PAID가 아니므로
        // 확정정산 합계에서 제외되어 이중 계산되지 않는다. 확정 이후 취소·환불(clawback)이 자동 반영된다.
        val net = cumulativeNet(settlement.sellerId)
        settlement.confirm(LocalDate.now(KST)) // 주기 종료 후에만 확정 가능(KST 기준)
        settlement.totalAmount = net

        // 0원 이하(신규 순정산 없음/이월)면 원장 분개·거래를 만들지 않는다 — Transaction은 양수 금액만 허용하므로
        // 0원 거래 생성은 예외가 되어 정산이 PENDING에 영구 고착된다. 음수(이월)도 지급 없이 상태만 기록한다.
        if (settlement.totalAmount.compareTo(BigDecimal.ZERO) > 0) {
            // 정산 확정 시 원장 기록: 판매자 미지급금(SELLER_PAYABLE) → 정산 확정 미지급금(SETTLEMENT_PAYABLE)
            val tx = transactionService.create(
                type = TransactionType.SETTLEMENT,
                amount = settlement.totalAmount,
                sellerId = settlement.sellerId,
            )
            ledgerService.record(
                debitAccount = AccountCode.SELLER_PAYABLE,
                creditAccount = AccountCode.SETTLEMENT_PAYABLE,
                amount = settlement.totalAmount,
                transactionId = tx.id,
                entryType = LedgerEntryType.SETTLEMENT,
            )
            tx.complete()
        }

        eventPublisher.publishEvent(
            SettlementConfirmedEvent(
                aggregateId = settlement.id,
                sellerId = settlement.sellerId,
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

    /** 확정된 정산을 지급 완료 처리한다. CONFIRMED 상태에서만 PAID로 전이 가능. */
    @Transactional
    fun markPaid(settlementId: Long): Settlement {
        val settlement = getById(settlementId)
        settlement.pay()
        return settlement
    }

    fun getById(id: Long): Settlement =
        settlementRepository.findById(id)
            .orElseThrow { BusinessException(ErrorCode.ENTITY_NOT_FOUND) }

    companion object {
        private val KST = ZoneId.of("Asia/Seoul")
    }
}
