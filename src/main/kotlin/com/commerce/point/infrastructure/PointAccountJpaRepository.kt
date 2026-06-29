package com.commerce.point.infrastructure

import com.commerce.point.domain.PointAccount
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal

interface PointAccountJpaRepository : JpaRepository<PointAccount, Long> {

    fun findByMemberId(memberId: Long): PointAccount?

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM PointAccount p WHERE p.memberId = :memberId")
    fun findByMemberIdForUpdate(@Param("memberId") memberId: Long): PointAccount?

    /**
     * 멤버의 포인트 계좌가 없을 때 원자적으로 생성한다. 이미 존재하면 무시한다(INSERT IGNORE).
     * SELECT FOR UPDATE + INSERT 패턴의 갭 락(gap lock) 데드락을 방지하기 위해
     * findByMemberIdForUpdate 호출 전에 먼저 행이 존재함을 보장한다.
     */
    @Transactional
    @Modifying
    @Query(
        value = "INSERT IGNORE INTO point_accounts (member_id, balance, version, created_at, updated_at) VALUES (:memberId, 0.0, 0, NOW(), NOW())",
        nativeQuery = true,
    )
    fun ensureExists(@Param("memberId") memberId: Long)

    @Query("SELECT COALESCE(SUM(p.balance), 0.0) FROM PointAccount p")
    fun sumAllBalances(): BigDecimal

    // 정합성 회귀 테스트/운영 보정용 — 캐시 잔액을 직접 덮어쓴다(@Transactional 내에서만 호출).
    @Transactional
    @Modifying
    @Query("UPDATE PointAccount p SET p.balance = :balance WHERE p.memberId = :memberId")
    fun overwriteBalance(@Param("memberId") memberId: Long, @Param("balance") balance: BigDecimal)
}
