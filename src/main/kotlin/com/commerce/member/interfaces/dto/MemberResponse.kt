package com.commerce.member.interfaces.dto

import com.commerce.member.domain.Member

data class MemberResponse(
    val id: Long,
    val email: String,
    val name: String,
    val status: String,
    val role: String,
) {
    companion object {
        fun from(member: Member) = MemberResponse(
            id = member.id,
            email = member.email,
            name = member.name,
            status = member.status.name,
            role = member.role.name,
        )
    }
}

data class TokenResponse(
    val accessToken: String,
    val memberId: Long,
    val role: String,
)
