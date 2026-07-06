package com.commerce.member.interfaces

import com.commerce.common.api.ApiResponse
import com.commerce.common.exception.BusinessException
import com.commerce.common.exception.ErrorCode
import com.commerce.common.security.SecurityUtils
import com.commerce.member.application.MemberService
import com.commerce.member.interfaces.dto.*
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/v1/members")
class MemberController(
    private val memberService: MemberService
) {

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    fun register(@Valid @RequestBody request: RegisterMemberRequest): ApiResponse<MemberResponse> =
        ApiResponse.ok(MemberResponse.from(memberService.register(request)))

    @PostMapping("/login")
    fun login(@Valid @RequestBody request: LoginRequest): ApiResponse<TokenResponse> =
        ApiResponse.ok(memberService.login(request))

    /** 회원 정보 조회 — 본인 또는 ADMIN만(PII 보호). 미인증은 시큐리티 필터에서 401로 차단된다. */
    @GetMapping("/{id}")
    fun getById(@PathVariable id: Long): ApiResponse<MemberResponse> {
        if (!SecurityUtils.isAdmin() && SecurityUtils.currentMemberId() != id)
            throw BusinessException(ErrorCode.ACCESS_DENIED)
        return ApiResponse.ok(MemberResponse.from(memberService.getById(id)))
    }

    @PostMapping("/{id}/suspend")
    fun suspend(@PathVariable id: Long): ApiResponse<MemberResponse> =
        ApiResponse.ok(MemberResponse.from(memberService.suspend(id)))

    @PostMapping("/{id}/unsuspend")
    fun unsuspend(@PathVariable id: Long): ApiResponse<MemberResponse> =
        ApiResponse.ok(MemberResponse.from(memberService.unsuspend(id)))

    @PostMapping("/{id}/withdraw")
    fun withdraw(@PathVariable id: Long): ApiResponse<MemberResponse> =
        ApiResponse.ok(MemberResponse.from(memberService.withdraw(id)))
}
