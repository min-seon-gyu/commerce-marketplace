package com.commerce.seller.domain

import com.commerce.common.domain.BaseEntity
import com.commerce.common.exception.BusinessException
import com.commerce.common.exception.ErrorCode
import jakarta.persistence.*
import java.math.BigDecimal
import java.time.LocalDate

enum class SettlementStatus {
    PENDING, CONFIRMED, PAID, DISPUTED
}

@Entity
@Table(
    name = "settlements",
    uniqueConstraints = [
        UniqueConstraint(name = "uk_settlement_period", columnNames = ["sellerId", "periodStart", "periodEnd"])
    ]
)
class Settlement(
    @Column(nullable = false)
    val sellerId: Long,

    @Column(nullable = false)
    val periodStart: LocalDate,

    @Column(nullable = false)
    val periodEnd: LocalDate,

    @Column(nullable = false, precision = 15, scale = 2)
    var totalAmount: BigDecimal,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 15)
    var status: SettlementStatus = SettlementStatus.PENDING,

    var disputeReason: String? = null,
) : BaseEntity() {

    /**
     * 확정. 정산 주기가 끝난 뒤([periodEnd] 다음 날부터)에만 허용한다 — 주기 중 확정은
     * 남은 기간의 매출·환불이 반영되기 전의 조기 지급 확정이 된다(예: 월간 정산을 3/13에 확정).
     * @param today 확정 기준일(KST)
     */
    fun confirm(today: LocalDate) {
        if (status != SettlementStatus.PENDING && status != SettlementStatus.DISPUTED)
            throw BusinessException(ErrorCode.INVALID_STATE_TRANSITION)
        if (!today.isAfter(periodEnd))
            throw BusinessException(ErrorCode.SETTLEMENT_PERIOD_NOT_ENDED)
        status = SettlementStatus.CONFIRMED
        disputeReason = null
    }

    fun dispute(reason: String) {
        if (status != SettlementStatus.PENDING)
            throw BusinessException(ErrorCode.INVALID_STATE_TRANSITION)
        status = SettlementStatus.DISPUTED
        disputeReason = reason
    }

    fun pay() {
        if (status != SettlementStatus.CONFIRMED)
            throw BusinessException(ErrorCode.INVALID_STATE_TRANSITION)
        status = SettlementStatus.PAID
    }
}
