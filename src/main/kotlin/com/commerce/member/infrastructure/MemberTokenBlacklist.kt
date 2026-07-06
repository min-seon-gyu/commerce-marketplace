package com.commerce.member.infrastructure

import com.commerce.member.domain.event.MemberSuspendedEvent
import com.commerce.member.domain.event.MemberUnsuspendedEvent
import com.commerce.member.domain.event.MemberWithdrawnEvent
import org.redisson.api.RedissonClient
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener
import java.time.Duration

/**
 * 정지/탈퇴 회원의 기존 JWT를 즉시 무효화하기 위한 Redis 블랙리스트.
 *
 * suspend/withdraw **커밋 이후**(AFTER_COMMIT) memberId를 등재하고, JwtAuthenticationFilter가 매 요청에서
 * 조회해 등재된 회원의 토큰이면 인증을 거부한다. unsuspend 시 해제한다.
 * 엔트리는 토큰 최대 수명(jwt.expiration)만큼만 유지하면 충분하다 — 그 이후엔 발급된 토큰이 이미 만료되고,
 * 정지/탈퇴 회원은 로그인이 막혀(MemberService.login의 isActive 검사) 새 토큰을 받을 수 없다.
 * Redis 장애 시 [isBlocked]는 false를 반환한다(가용성 우선 — 장애가 전체 인증을 막지 않도록).
 */
@Component
class MemberTokenBlacklist(
    private val redisson: RedissonClient,
    @Value("\${jwt.expiration:86400000}") private val tokenTtlMs: Long,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    private fun key(memberId: Long) = "auth:blacklist:member:$memberId"

    /** 해당 회원의 토큰이 무효화(정지/탈퇴)되었는지. Redis 장애 시 false(허용). */
    fun isBlocked(memberId: Long): Boolean =
        try {
            redisson.getBucket<String>(key(memberId)).get() != null
        } catch (e: Exception) {
            log.warn("Token blacklist check failed for member {} (allowing): {}", memberId, e.message)
            false
        }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    fun onSuspended(event: MemberSuspendedEvent) = block(event.aggregateId)

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    fun onWithdrawn(event: MemberWithdrawnEvent) = block(event.aggregateId)

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    fun onUnsuspended(event: MemberUnsuspendedEvent) = unblock(event.aggregateId)

    private fun block(memberId: Long) {
        try {
            redisson.getBucket<String>(key(memberId)).set("1", Duration.ofMillis(tokenTtlMs))
        } catch (e: Exception) {
            log.warn("Failed to blacklist member {}: {}", memberId, e.message)
        }
    }

    private fun unblock(memberId: Long) {
        try {
            redisson.getBucket<String>(key(memberId)).delete()
        } catch (e: Exception) {
            log.warn("Failed to un-blacklist member {}: {}", memberId, e.message)
        }
    }
}
