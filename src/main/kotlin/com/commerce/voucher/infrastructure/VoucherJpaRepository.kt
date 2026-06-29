package com.commerce.voucher.infrastructure

import com.commerce.voucher.domain.Voucher
import com.commerce.voucher.domain.VoucherStatus
import jakarta.persistence.LockModeType
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.math.BigDecimal
import java.time.LocalDateTime

interface VoucherJpaRepository : JpaRepository<Voucher, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT v FROM Voucher v WHERE v.id = :id")
    fun findByIdForUpdate(@Param("id") id: Long): Voucher?

    fun findByMemberIdAndStatus(memberId: Long, status: VoucherStatus): List<Voucher>

    @Query("SELECT COALESCE(SUM(v.faceValue), 0) FROM Voucher v WHERE v.memberId = :memberId AND v.regionId = :regionId")
    fun sumFaceValueByMemberAndRegion(memberId: Long, regionId: Long): BigDecimal

    @Query("SELECT v.id FROM Voucher v WHERE v.status IN :statuses AND v.expiresAt < :now ORDER BY v.expiresAt ASC")
    fun findExpiredVoucherIds(
        @Param("statuses") statuses: List<VoucherStatus>,
        @Param("now") now: LocalDateTime,
        limit: Pageable,
    ): List<Long>

    @Query("SELECT COALESCE(SUM(v.faceValue), 0) FROM Voucher v WHERE v.regionId = :regionId AND v.purchasedAt >= :start AND v.purchasedAt < :end")
    fun sumFaceValueByRegionAndMonth(regionId: Long, start: LocalDateTime, end: LocalDateTime): BigDecimal

    @org.springframework.data.jpa.repository.Modifying
    @Query("UPDATE Voucher v SET v.expiresAt = :expiresAt WHERE v.id = :id")
    fun updateExpiresAt(@Param("id") id: Long, @Param("expiresAt") expiresAt: LocalDateTime)

    @org.springframework.data.jpa.repository.Modifying
    @Query("UPDATE Voucher v SET v.purchasedAt = :purchasedAt WHERE v.id = :id")
    fun updatePurchasedAt(@Param("id") id: Long, @Param("purchasedAt") purchasedAt: LocalDateTime)
}
