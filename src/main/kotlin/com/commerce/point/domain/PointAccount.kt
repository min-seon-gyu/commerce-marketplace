package com.commerce.point.domain

import com.commerce.common.domain.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table
import java.math.BigDecimal

@Entity
@Table(name = "point_accounts")
class PointAccount(
    @Column(nullable = false, unique = true)
    val memberId: Long,

    @Column(nullable = false)
    var balance: BigDecimal = BigDecimal.ZERO,
) : BaseEntity() {

    init {
        require(balance >= BigDecimal.ZERO) { "포인트 잔액은 0 이상이어야 합니다" }
    }

    /** 적립: 캐시 잔액 증가. 원장 분개는 PointEarnService가 동기로 기록한다. */
    fun earn(amount: BigDecimal) {
        require(amount > BigDecimal.ZERO) { "적립액은 0보다 커야 합니다" }
        balance += amount
    }

    /**
     * 적립 취소(보상): 캐시 잔액 차감. 원장 역분개는 PointEarnService.reverseEarn이 동기로 기록한다.
     * 포인트는 아직 사용 불가이므로 취소 시점에 잔액은 항상 적립액 이상이다(가드로 음수 방지).
     */
    fun deduct(amount: BigDecimal) {
        require(amount > BigDecimal.ZERO) { "차감액은 0보다 커야 합니다" }
        require(balance >= amount) { "포인트 잔액이 부족합니다" }
        balance -= amount
    }
}
