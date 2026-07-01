package com.commerce.inventory.domain

import com.commerce.common.domain.BaseEntity
import com.commerce.common.exception.BusinessException
import com.commerce.common.exception.ErrorCode
import jakarta.persistence.*

/**
 * SKU별 재고. 주문 확정 시 [deduct]로 차감하고, 취소/환불 시 [restore]로 복원한다.
 * 동시 차감의 초과판매(oversell)는 상위 [com.commerce.inventory.application.StockService]의
 * 분산락 + DB 비관적 락(SELECT FOR UPDATE) 이중 방어로 막는다.
 */
@Entity
@Table(
    name = "stocks",
    uniqueConstraints = [UniqueConstraint(name = "uk_stock_sku", columnNames = ["skuId"])],
)
class Stock(
    @Column(name = "sku_id", nullable = false)
    val skuId: Long,

    @Column(nullable = false)
    var quantity: Int,
) : BaseEntity() {

    /** 재고 차감. 부족하면 예외(초과판매 방지 불변식: quantity >= 0). */
    fun deduct(qty: Int) {
        require(qty > 0) { "차감 수량은 양수여야 합니다" }
        if (quantity < qty) throw BusinessException(ErrorCode.OUT_OF_STOCK)
        quantity -= qty
    }

    /** 재고 복원(주문 취소/환불). */
    fun restore(qty: Int) {
        require(qty > 0) { "복원 수량은 양수여야 합니다" }
        quantity += qty
    }

    /** 재고 실수량 조정(입고/정정). 음수 불가. */
    fun adjustTo(newQuantity: Int) {
        require(newQuantity >= 0) { "재고 수량은 0 이상이어야 합니다" }
        quantity = newQuantity
    }
}
