package com.commerce.claim.domain

import com.commerce.common.domain.BaseEntity
import jakarta.persistence.*

/** 반품 클레임 대상 주문 라인. 승인 시 이 라인들이 환불된다. */
@Entity
@Table(name = "return_claim_lines", indexes = [Index(name = "idx_return_claim_line_claim", columnList = "claim_id")])
class ReturnClaimLine(
    @Column(name = "claim_id", nullable = false)
    val claimId: Long,

    @Column(name = "order_line_id", nullable = false)
    val orderLineId: Long,
) : BaseEntity()
