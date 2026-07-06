package com.commerce.config

import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.core.env.Environment
import org.springframework.stereotype.Component
import java.util.*
import javax.crypto.SecretKey

@Component
class JwtTokenProvider(
    @Value("\${jwt.secret:#{null}}") private val rawSecret: String?,
    @Value("\${jwt.expiration:86400000}") private val expirationMs: Long,
    private val env: Environment,
) {
    companion object {
        private val log = LoggerFactory.getLogger(JwtTokenProvider::class.java)
        const val DEV_SECRET = "local-dev-only-secret-key-minimum-256-bits-long-do-not-use-in-production!!"
    }

    private val key: SecretKey = Keys.hmacShaKeyFor((rawSecret ?: DEV_SECRET).toByteArray())

    @PostConstruct
    fun validateSecret() {
        val usingDevSecret = rawSecret.isNullOrBlank() || rawSecret == DEV_SECRET
        if (!usingDevSecret) return // 명시적 시크릿이 설정됨 → OK

        // dev 시크릿 폴백은 local/test/dev 프로파일에서만 허용한다. 그 외(프로파일 미지정 포함)에는
        // 기동을 실패시킨다 — 프로파일 누락 배포가 레포에 커밋된 dev 시크릿으로 조용히 기동돼
        // 누구나 토큰(예: ADMIN)을 위조하는 것을 막는다.
        val devProfiles = setOf("local", "test", "dev")
        if (env.activeProfiles.none { it in devProfiles }) {
            throw IllegalStateException(
                "JWT secret(jwt.secret) must be set unless running with a local/test/dev profile " +
                    "(active profiles: ${env.activeProfiles.joinToString().ifBlank { "none" }})"
            )
        }
        log.warn("⚠️  dev-only JWT secret in use — DO NOT use in production")
    }

    fun generateToken(memberId: Long, role: String): String {
        val now = Date()
        return Jwts.builder()
            .subject(memberId.toString())
            .claim("role", role)
            .issuedAt(now)
            .expiration(Date(now.time + expirationMs))
            .signWith(key)
            .compact()
    }

    fun getMemberIdFromToken(token: String): Long {
        val claims = Jwts.parser().verifyWith(key).build().parseSignedClaims(token).payload
        return claims.subject.toLong()
    }

    fun getRoleFromToken(token: String): String {
        val claims = Jwts.parser().verifyWith(key).build().parseSignedClaims(token).payload
        return claims.get("role", String::class.java) ?: "USER"
    }

    fun validateToken(token: String): Boolean {
        return try {
            Jwts.parser().verifyWith(key).build().parseSignedClaims(token)
            true
        } catch (e: Exception) {
            false
        }
    }
}
