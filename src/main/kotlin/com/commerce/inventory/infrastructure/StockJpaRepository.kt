package com.commerce.inventory.infrastructure

import com.commerce.inventory.domain.Stock
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface StockJpaRepository : JpaRepository<Stock, Long> {
    fun findBySkuId(skuId: Long): Stock?

    /** 동시 차감 직렬화를 위한 비관적 락(SELECT ... FOR UPDATE). 분산락과 이중 방어. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM Stock s WHERE s.skuId = :skuId")
    fun findBySkuIdForUpdate(@Param("skuId") skuId: Long): Stock?

    fun findBySkuIdIn(skuIds: Collection<Long>): List<Stock>
}
