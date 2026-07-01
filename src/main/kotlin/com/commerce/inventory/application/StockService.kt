package com.commerce.inventory.application

import com.commerce.common.exception.BusinessException
import com.commerce.common.exception.ErrorCode
import com.commerce.inventory.domain.Stock
import com.commerce.inventory.infrastructure.StockJpaRepository
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate

/**
 * SKU 재고 관리. 차감/복원은 **분산락 → 트랜잭션(커밋) → 락 해제** 순서로 처리해
 * 락 해제 후 다른 스레드가 커밋 전 재고를 읽는 문제를 막고, DB 비관적 락(SELECT FOR UPDATE)으로 이중 방어한다.
 * 초과판매(oversell) 불변식: quantity >= 0.
 */
@Service
class StockService(
    private val stockRepository: StockJpaRepository,
    private val lockManager: StockLockManager,
    private val transactionTemplate: TransactionTemplate,
    private val meterRegistry: MeterRegistry,
) {

    /** SKU 재고 행 생성(상품 등록 시). 이미 있으면 예외. */
    @Transactional
    fun createStock(skuId: Long, initialQuantity: Int): Stock {
        require(initialQuantity >= 0) { "초기 재고는 0 이상이어야 합니다" }
        if (stockRepository.findBySkuId(skuId) != null)
            throw BusinessException(ErrorCode.INVALID_INPUT, "이미 재고가 존재하는 SKU입니다")
        return stockRepository.save(Stock(skuId = skuId, quantity = initialQuantity))
    }

    /** 재고 차감. 부족하면 OUT_OF_STOCK. 차감 후 잔여 수량 반환. */
    fun deduct(skuId: Long, quantity: Int): Int = lockManager.withStockLock(skuId) {
        val timer = Timer.start(meterRegistry)
        try {
            val remaining = transactionTemplate.execute { _ ->
                val stock = stockRepository.findBySkuIdForUpdate(skuId)
                    ?: throw BusinessException(ErrorCode.ENTITY_NOT_FOUND)
                stock.deduct(quantity)
                stock.quantity
            }!!
            meterRegistry.counter("stock.deduct.count", "result", "success").increment()
            remaining
        } catch (e: Exception) {
            meterRegistry.counter("stock.deduct.count", "result", "failure").increment()
            throw e
        } finally {
            timer.stop(meterRegistry.timer("stock.deduct.duration"))
        }
    }

    /** 재고 복원(주문 취소/환불). 복원 후 잔여 수량 반환. */
    fun restore(skuId: Long, quantity: Int): Int = lockManager.withStockLock(skuId) {
        transactionTemplate.execute { _ ->
            val stock = stockRepository.findBySkuIdForUpdate(skuId)
                ?: throw BusinessException(ErrorCode.ENTITY_NOT_FOUND)
            stock.restore(quantity)
            stock.quantity
        }!!
    }

    /** 재고 실수량 조정(입고/정정). */
    fun adjust(skuId: Long, newQuantity: Int): Int = lockManager.withStockLock(skuId) {
        transactionTemplate.execute { _ ->
            val stock = stockRepository.findBySkuIdForUpdate(skuId)
                ?: throw BusinessException(ErrorCode.ENTITY_NOT_FOUND)
            stock.adjustTo(newQuantity)
            stock.quantity
        }!!
    }

    @Transactional(readOnly = true)
    fun getBySkuId(skuId: Long): Stock =
        stockRepository.findBySkuId(skuId) ?: throw BusinessException(ErrorCode.ENTITY_NOT_FOUND)

    /** 여러 SKU의 현재 재고를 일괄 조회(N+1 방지). */
    @Transactional(readOnly = true)
    fun quantitiesBySkuIds(skuIds: Collection<Long>): Map<Long, Int> =
        if (skuIds.isEmpty()) emptyMap()
        else stockRepository.findBySkuIdIn(skuIds).associate { it.skuId to it.quantity }
}
