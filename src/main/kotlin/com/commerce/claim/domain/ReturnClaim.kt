package com.commerce.claim.domain

import com.commerce.common.domain.BaseEntity
import com.commerce.common.exception.BusinessException
import com.commerce.common.exception.ErrorCode
import jakarta.persistence.*

enum class ReturnClaimStatus { REQUESTED, COMPLETED, REJECTED }

/** 반품 사유. 귀책 판단·반품배송비 정책의 근거가 되나, 현재 슬라이스는 사유 기록까지만 다룬다. */
enum class ReturnReason { CHANGED_MIND, DEFECTIVE, WRONG_DELIVERY, OTHER }

/**
 * 반품 클레임(주문 라인 단위). 배송완료(DELIVERED) 이후 구매자가 요청하고, 운영자가 승인/거절한다.
 * 승인 시 대상 라인에 대해 주문 환불(refundLines)이 같은 트랜잭션에서 실행되고 COMPLETED로 전이한다.
 * 대상 라인은 [ReturnClaimLine]으로 보관한다.
 */
@Entity
@Table(
    name = "return_claims",
    indexes = [
        Index(name = "idx_return_claim_order", columnList = "order_id"),
        Index(name = "idx_return_claim_member", columnList = "member_id, status"),
    ],
)
class ReturnClaim(
    @Column(name = "order_id", nullable = false)
    val orderId: Long,

    @Column(name = "member_id", nullable = false)
    val memberId: Long,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    val reason: ReturnReason,

    @Column(length = 500)
    val detail: String? = null,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var status: ReturnClaimStatus = ReturnClaimStatus.REQUESTED,
) : BaseEntity() {

    fun complete() {
        if (status != ReturnClaimStatus.REQUESTED) throw BusinessException(ErrorCode.CLAIM_NOT_PENDING)
        status = ReturnClaimStatus.COMPLETED
    }

    fun reject() {
        if (status != ReturnClaimStatus.REQUESTED) throw BusinessException(ErrorCode.CLAIM_NOT_PENDING)
        status = ReturnClaimStatus.REJECTED
    }
}
