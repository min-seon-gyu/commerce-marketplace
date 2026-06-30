package com.commerce.member.interfaces.dto

import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank

data class RegisterMemberRequest(
    @field:Email val email: String,
    @field:NotBlank val name: String,
    @field:NotBlank val password: String,
)

data class LoginRequest(
    @field:Email val email: String,
    @field:NotBlank val password: String,
)
