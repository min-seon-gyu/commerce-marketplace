package com.commerce.config

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

/**
 * Authorization: Bearer <jwt> 헤더를 읽어 검증하고,
 * SecurityContextHolder에 principal=memberId(Long), 권한=ROLE_<role> 인증을 주입한다.
 * 토큰이 없거나 유효하지 않으면 컨텍스트를 비워두어(익명) 보호 엔드포인트에서 401이 되게 한다.
 */
@Component
class JwtAuthenticationFilter(
    private val jwtTokenProvider: JwtTokenProvider,
) : OncePerRequestFilter() {

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val header = request.getHeader("Authorization")
        if (header != null && header.startsWith(BEARER_PREFIX)) {
            val token = header.substring(BEARER_PREFIX.length)
            try {
                if (jwtTokenProvider.validateToken(token)) {
                    val memberId: Long = jwtTokenProvider.getMemberIdFromToken(token)
                    val role: String = jwtTokenProvider.getRoleFromToken(token)
                    val authentication = UsernamePasswordAuthenticationToken(
                        memberId,
                        null,
                        listOf(SimpleGrantedAuthority("ROLE_$role")),
                    )
                    authentication.details = WebAuthenticationDetailsSource().buildDetails(request)
                    SecurityContextHolder.getContext().authentication = authentication
                }
            } catch (e: Exception) {
                SecurityContextHolder.clearContext()
            }
        }
        filterChain.doFilter(request, response)
    }

    companion object {
        private const val BEARER_PREFIX = "Bearer "
    }
}
