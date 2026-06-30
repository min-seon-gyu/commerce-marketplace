package com.commerce.member.interfaces

import com.commerce.common.api.ApiResponse
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

    @GetMapping("/{id}")
    fun getById(@PathVariable id: Long): ApiResponse<MemberResponse> =
        ApiResponse.ok(MemberResponse.from(memberService.getById(id)))

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
