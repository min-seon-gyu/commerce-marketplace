package com.commerce.claim.infrastructure

import com.commerce.claim.domain.ReturnClaim
import com.commerce.claim.domain.ReturnClaimLine
import com.commerce.claim.domain.ReturnClaimStatus
import org.springframework.data.jpa.repository.JpaRepository

interface ReturnClaimJpaRepository : JpaRepository<ReturnClaim, Long> {
    fun findByOrderIdAndStatus(orderId: Long, status: ReturnClaimStatus): List<ReturnClaim>
}

interface ReturnClaimLineJpaRepository : JpaRepository<ReturnClaimLine, Long> {
    fun findByClaimId(claimId: Long): List<ReturnClaimLine>
    fun findByClaimIdIn(claimIds: Collection<Long>): List<ReturnClaimLine>
}
