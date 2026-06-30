package com.commerce.member.interfaces

import com.commerce.common.api.ApiResponse
import com.commerce.common.security.SecurityUtils
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1")
class AuthController {

    /** 현재 인증된 회원 신원 확인용 엔드포인트. JWT 필터/principal 헬퍼 동작의 레퍼런스. */
    @GetMapping("/me")
    fun me(): ApiResponse<MeResponse> = ApiResponse.ok(MeResponse(memberId = SecurityUtils.currentMemberId()))
}

data class MeResponse(val memberId: Long)
