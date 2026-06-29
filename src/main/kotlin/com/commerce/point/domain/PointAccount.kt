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
}
