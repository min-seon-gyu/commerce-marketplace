package com.commerce.point.infrastructure

import com.commerce.point.domain.PointTransaction
import org.springframework.data.jpa.repository.JpaRepository

interface PointTransactionJpaRepository : JpaRepository<PointTransaction, Long> {

    fun findByMemberIdOrderByCreatedAtDesc(memberId: Long): List<PointTransaction>

    fun findBySourceTransactionId(sourceTransactionId: Long): List<PointTransaction>
}
