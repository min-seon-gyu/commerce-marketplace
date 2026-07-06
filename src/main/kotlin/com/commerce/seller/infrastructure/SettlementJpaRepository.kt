package com.commerce.seller.infrastructure

import com.commerce.seller.domain.Settlement
import com.commerce.seller.domain.SettlementStatus
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.math.BigDecimal
import java.time.LocalDate

interface SettlementJpaRepository : JpaRepository<Settlement, Long> {
    fun findBySellerIdAndPeriodStartAndPeriodEnd(
        sellerId: Long,
        periodStart: LocalDate,
        periodEnd: LocalDate,
    ): Settlement?

    fun countByStatus(status: SettlementStatus): Long

    /** 배치 검증용: 특정 상태이면서 금액이 임계 이하(예: 0원)인 정산 수 — 있으면 이상치. */
    fun countByStatusAndTotalAmountLessThanEqual(status: SettlementStatus, amount: BigDecimal): Long

    /** 해당 판매자에 주어진 날짜를 포함하는 정산이 주어진 상태(예: CONFIRMED/PAID)로 존재하는지. */
    fun existsBySellerIdAndStatusInAndPeriodStartLessThanEqualAndPeriodEndGreaterThanEqual(
        sellerId: Long,
        statuses: Collection<SettlementStatus>,
        periodStartUpperBound: LocalDate,
        periodEndLowerBound: LocalDate,
    ): Boolean

    /** 판매자에게 이미 확정/지급(CONFIRMED, PAID)된 정산액 합 — 누적 정산에서 당기 순정산액 계산에 쓴다. */
    @Query("select coalesce(sum(s.totalAmount), 0) from Settlement s where s.sellerId = :sellerId and s.status in :statuses")
    fun sumAmountBySellerAndStatusIn(
        @Param("sellerId") sellerId: Long,
        @Param("statuses") statuses: Collection<SettlementStatus>,
    ): BigDecimal
}
