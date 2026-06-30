package com.commerce.common.idempotency

import org.redisson.api.RedissonClient
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Duration

data class CachedResponse(val status: Int, val body: String)

/**
 * 멱등성 저장소.
 *
 * 핵심: DB의 idempotencyKey UNIQUE 제약을 분산 뮤텍스로 사용한다.
 * 처리 "전에" IN_PROGRESS 행을 선점(INSERT)하므로, 같은 키로 동시에 들어온 요청은
 * 단 하나만 선점에 성공하고 나머지는 제약 위반으로 걸러진다. (= 동시 이중 처리 차단)
 */
@Component
class IdempotencyStore(
    private val redissonClient: RedissonClient,
    private val repository: IdempotencyRepository,
) {
    private val redisTtl = Duration.ofHours(24)
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Redis 빠른 경로: 완료된 응답이 캐시에 있으면 반환 (순차 재시도 대응).
     * Redis 장애 시에는 null을 반환해 DB 폴백 경로로 안전하게 강등한다(가용성 우선).
     */
    fun findCachedResponse(key: String): CachedResponse? {
        return try {
            redissonClient.getBucket<String>(redisKey(key)).get()?.let { parse(it) }
        } catch (e: Exception) {
            log.warn("Redis lookup failed for idempotency key {}, falling back to DB: {}", key, e.message)
            null
        }
    }

    /**
     * 멱등키 선점(INSERT). 이미 존재하면 DataIntegrityViolationException을 던진다.
     * 호출 측(interceptor)에서 예외를 잡아 "선점 실패"로 처리한다.
     * 별도 트랜잭션에서 즉시 커밋되어야 다른 동시 요청에 보인다.
     */
    @Transactional
    fun reserve(key: String) {
        repository.saveAndFlush(IdempotencyKey(idempotencyKey = key))
    }

    /** 처리 성공 → DB를 COMPLETED로 전환(영속 저장소 우선)하고 응답을 캐시(Redis 실패와 독립) */
    @Transactional
    fun complete(key: String, responseBody: String, responseStatus: Int) {
        repository.findByIdempotencyKey(key)?.complete(responseBody, responseStatus)
        cacheQuietly(key, responseStatus, responseBody)
    }

    /** 처리 실패/예외 → 선점 해제(행 삭제)하여 재시도가 가능하게 한다 */
    @Transactional
    fun release(key: String) {
        repository.deleteByIdempotencyKey(key)
    }

    /** Redis 미스 시 DB에서 완료된 응답 조회(+Redis 워밍, 실패해도 무시) */
    fun findCompletedInDb(key: String): CachedResponse? {
        val record = repository.findByIdempotencyKey(key) ?: return null
        if (record.status != IdempotencyStatus.COMPLETED) return null
        val body = record.responseBody ?: return null
        val status = record.responseStatus ?: 200
        cacheQuietly(key, status, body)
        return CachedResponse(status, body)
    }

    /** Redis 캐시 기록 — 실패는 로깅만 하고 무시(DB가 source of truth) */
    private fun cacheQuietly(key: String, status: Int, body: String) {
        try {
            redissonClient.getBucket<String>(redisKey(key)).set("$status|$body", redisTtl)
        } catch (e: Exception) {
            log.warn("Failed to cache idempotency key {} in Redis: {}", key, e.message)
        }
    }

    private fun redisKey(key: String) = "idempotency:$key"

    private fun parse(cached: String): CachedResponse {
        val i = cached.indexOf('|')
        if (i == -1) return CachedResponse(200, cached)
        val status = cached.substring(0, i).toIntOrNull() ?: 200
        return CachedResponse(status, cached.substring(i + 1))
    }
}
