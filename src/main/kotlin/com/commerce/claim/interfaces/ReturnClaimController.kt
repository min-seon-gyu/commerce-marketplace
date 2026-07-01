package com.commerce.claim.interfaces

import com.commerce.claim.application.ReturnClaimDetail
import com.commerce.claim.application.ReturnClaimService
import com.commerce.claim.domain.ReturnReason
import com.commerce.common.api.ApiResponse
import com.commerce.common.exception.BusinessException
import com.commerce.common.exception.ErrorCode
import com.commerce.common.security.SecurityUtils
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.*

data class CreateReturnClaimRequest(
    val orderLineIds: List<Long>,
    val reason: ReturnReason,
    val detail: String? = null,
)

data class ReturnClaimResponse(
    val id: Long,
    val orderId: Long,
    val memberId: Long,
    val status: String,
    val reason: String,
    val detail: String?,
    val orderLineIds: List<Long>,
) {
    companion object {
        fun from(d: ReturnClaimDetail) = ReturnClaimResponse(
            id = d.claim.id,
            orderId = d.claim.orderId,
            memberId = d.claim.memberId,
            status = d.claim.status.name,
            reason = d.claim.reason.name,
            detail = d.claim.detail,
            orderLineIds = d.orderLineIds,
        )
    }
}

/** 반품 클레임 요청/조회(구매자). 요청은 배송완료 후 본인 주문만. 승인/거절은 [AdminReturnClaimController]. */
@RestController
class ReturnClaimController(
    private val returnClaimService: ReturnClaimService,
) {

    @PostMapping("/api/v1/orders/{orderId}/return-claims")
    @ResponseStatus(HttpStatus.CREATED)
    fun request(@PathVariable orderId: Long, @RequestBody request: CreateReturnClaimRequest): ApiResponse<ReturnClaimResponse> {
        val claim = returnClaimService.requestReturn(
            SecurityUtils.currentMemberId(), orderId, request.orderLineIds, request.reason, request.detail,
        )
        return ApiResponse.ok(ReturnClaimResponse.from(returnClaimService.getDetail(claim.id)))
    }

    @GetMapping("/api/v1/return-claims/{claimId}")
    fun get(@PathVariable claimId: Long): ApiResponse<ReturnClaimResponse> {
        val detail = returnClaimService.getDetail(claimId)
        if (!SecurityUtils.isAdmin() && detail.claim.memberId != SecurityUtils.currentMemberId())
            throw BusinessException(ErrorCode.ACCESS_DENIED)
        return ApiResponse.ok(ReturnClaimResponse.from(detail))
    }
}

/** 반품 클레임 처리(관리자). 경로가 `/api/v1/admin` 하위이므로 ADMIN 게이트된다. */
@RestController
@RequestMapping("/api/v1/admin/return-claims")
class AdminReturnClaimController(
    private val returnClaimService: ReturnClaimService,
) {

    @PostMapping("/{claimId}/approve")
    fun approve(@PathVariable claimId: Long): ApiResponse<ReturnClaimResponse> {
        returnClaimService.approveReturn(claimId)
        return ApiResponse.ok(ReturnClaimResponse.from(returnClaimService.getDetail(claimId)))
    }

    @PostMapping("/{claimId}/reject")
    fun reject(@PathVariable claimId: Long): ApiResponse<ReturnClaimResponse> {
        returnClaimService.rejectReturn(claimId)
        return ApiResponse.ok(ReturnClaimResponse.from(returnClaimService.getDetail(claimId)))
    }
}
