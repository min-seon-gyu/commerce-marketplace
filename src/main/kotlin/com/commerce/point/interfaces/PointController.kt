package com.commerce.point.interfaces

import com.commerce.common.api.ApiResponse
import com.commerce.common.exception.BusinessException
import com.commerce.common.exception.ErrorCode
import com.commerce.common.security.SecurityUtils
import com.commerce.point.application.PointQueryService
import com.commerce.point.interfaces.dto.PointBalanceResponse
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/members/{memberId}/points")
class PointController(
    private val pointQueryService: PointQueryService,
) {

    @GetMapping
    fun getPoints(@PathVariable memberId: Long): ApiResponse<PointBalanceResponse> {
        // 인증: SecurityUtils.currentMemberId()(Plan 1) — 미인증이면 UNAUTHORIZED(401)를 던진다.
        // 인가: 경로 memberId가 인증 주체와 다르면 본인 자원이 아니므로 ACCESS_DENIED(403).
        if (SecurityUtils.currentMemberId() != memberId) throw BusinessException(ErrorCode.ACCESS_DENIED)
        return ApiResponse.ok(pointQueryService.getBalance(memberId))
    }
}
