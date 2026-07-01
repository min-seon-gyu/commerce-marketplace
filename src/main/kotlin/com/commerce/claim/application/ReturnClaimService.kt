package com.commerce.claim.application

import com.commerce.claim.domain.ReturnClaim
import com.commerce.claim.domain.ReturnClaimLine
import com.commerce.claim.domain.ReturnClaimStatus
import com.commerce.claim.domain.ReturnReason
import com.commerce.claim.infrastructure.ReturnClaimJpaRepository
import com.commerce.claim.infrastructure.ReturnClaimLineJpaRepository
import com.commerce.common.exception.BusinessException
import com.commerce.common.exception.ErrorCode
import com.commerce.order.application.OrderService
import com.commerce.shipping.application.ShippingService
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

data class ReturnClaimDetail(val claim: ReturnClaim, val orderLineIds: List<Long>)

/**
 * 반품 클레임 워크플로우: 요청(구매자) → 승인/거절(운영자). 승인 시 대상 라인을 주문 환불로 정산한다.
 *
 * 요청 게이트: 주문 배송완료(DELIVERED) 이후 + 본인 주문 + 대상 라인이 미환불 + 진행 중(REQUESTED) 클레임에 미포함.
 * 승인: [OrderService.refundLines]를 호출하며, 클레임 완료(COMPLETED) 전이를 **같은 트랜잭션의 콜백**으로 실행해
 * 환불과 클레임 상태를 원자적으로 커밋한다(부분 성공으로 클레임이 REQUESTED에 남는 것을 방지).
 *
 * 동시성 단순화(의도적): 같은 라인에 대한 동시 요청, 또는 같은 주문의 서로 다른 라인 클레임 동시 승인은
 * refundLines의 재고락 + in-tx `refunded` 재확인 + Order @Version으로 **이중 환불이 차단**된다.
 * 다만 경합에서 밀린 쪽은 환불되지 않고 REQUESTED로 남을 수 있어(재무 손실 없음), 운영자가 재시도/거절로 정리한다.
 */
@Service
class ReturnClaimService(
    private val returnClaimRepository: ReturnClaimJpaRepository,
    private val returnClaimLineRepository: ReturnClaimLineJpaRepository,
    private val orderService: OrderService,
    private val shippingService: ShippingService,
) {

    @Transactional
    fun requestReturn(
        requesterMemberId: Long,
        orderId: Long,
        orderLineIds: List<Long>,
        reason: ReturnReason,
        detail: String? = null,
    ): ReturnClaim {
        if (orderLineIds.isEmpty()) throw BusinessException(ErrorCode.INVALID_CLAIM_LINES)
        val orderDetail = orderService.getDetail(orderId)
        if (orderDetail.order.memberId != requesterMemberId) throw BusinessException(ErrorCode.ACCESS_DENIED)
        if (!shippingService.isDelivered(orderId)) throw BusinessException(ErrorCode.RETURN_NOT_ALLOWED)

        val targetIds = orderLineIds.toSet()
        val lines = orderDetail.lines.filter { it.id in targetIds }
        if (lines.size != targetIds.size) throw BusinessException(ErrorCode.INVALID_CLAIM_LINES) // 이 주문에 없는 라인
        if (lines.any { it.refunded }) throw BusinessException(ErrorCode.INVALID_CLAIM_LINES)       // 이미 환불된 라인

        // 진행 중(REQUESTED) 클레임이 선점한 라인과 겹치면 거부(중복 반품 방지).
        val openClaims = returnClaimRepository.findByOrderIdAndStatus(orderId, ReturnClaimStatus.REQUESTED)
        if (openClaims.isNotEmpty()) {
            val lockedLineIds = returnClaimLineRepository.findByClaimIdIn(openClaims.map { it.id })
                .map { it.orderLineId }.toSet()
            if (targetIds.any { it in lockedLineIds }) throw BusinessException(ErrorCode.LINE_ALREADY_IN_CLAIM)
        }

        val claim = returnClaimRepository.save(
            ReturnClaim(orderId = orderId, memberId = requesterMemberId, reason = reason, detail = detail)
        )
        targetIds.forEach { returnClaimLineRepository.save(ReturnClaimLine(claim.id, it)) }
        return claim
    }

    /** 승인 → 대상 라인 환불 + 클레임 COMPLETED(같은 트랜잭션). refundLines가 자체 락/트랜잭션을 관리한다. */
    fun approveReturn(claimId: Long): ReturnClaim {
        val claim = returnClaimRepository.findById(claimId)
            .orElseThrow { BusinessException(ErrorCode.RETURN_CLAIM_NOT_FOUND) }
        if (claim.status != ReturnClaimStatus.REQUESTED) throw BusinessException(ErrorCode.CLAIM_NOT_PENDING)

        val lineIds = returnClaimLineRepository.findByClaimId(claimId).map { it.orderLineId }
        orderService.refundLines(claim.memberId, claim.orderId, lineIds) {
            // 환불과 동일 트랜잭션에서 클레임을 완료 처리(원자성).
            returnClaimRepository.findById(claimId)
                .orElseThrow { BusinessException(ErrorCode.RETURN_CLAIM_NOT_FOUND) }
                .complete()
        }
        return returnClaimRepository.findById(claimId)
            .orElseThrow { BusinessException(ErrorCode.RETURN_CLAIM_NOT_FOUND) }
    }

    @Transactional
    fun rejectReturn(claimId: Long): ReturnClaim {
        val claim = returnClaimRepository.findById(claimId)
            .orElseThrow { BusinessException(ErrorCode.RETURN_CLAIM_NOT_FOUND) }
        claim.reject()
        return claim
    }

    @Transactional(readOnly = true)
    fun getDetail(claimId: Long): ReturnClaimDetail {
        val claim = returnClaimRepository.findById(claimId)
            .orElseThrow { BusinessException(ErrorCode.RETURN_CLAIM_NOT_FOUND) }
        val lineIds = returnClaimLineRepository.findByClaimId(claimId).map { it.orderLineId }
        return ReturnClaimDetail(claim, lineIds)
    }
}
