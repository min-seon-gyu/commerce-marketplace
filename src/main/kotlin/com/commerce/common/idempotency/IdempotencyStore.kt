package com.commerce.common.idempotency

import org.redisson.api.RedissonClient
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

    /** Redis 빠른 경로: 완료된 응답이 캐시에 있으면 반환 (순차 재시도 대응) */
    fun findCachedResponse(key: String): CachedResponse? {
        val cached = redissonClient.getBucket<String>(redisKey(key)).get() ?: return null
        return parse(cached)
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

    /** 처리 성공 → DB를 COMPLETED로 전환하고 응답을 캐시 */
    @Transactional
    fun complete(key: String, responseBody: String, responseStatus: Int) {
        repository.findByIdempotencyKey(key)?.complete(responseBody, responseStatus)
        redissonClient.getBucket<String>(redisKey(key)).set("$responseStatus|$responseBody", redisTtl)
    }

    /** 처리 실패/예외 → 선점 해제(행 삭제)하여 재시도가 가능하게 한다 */
    @Transactional
    fun release(key: String) {
        repository.deleteByIdempotencyKey(key)
    }

    /** Redis 미스 시 DB에서 완료된 응답 조회(+Redis 워밍) */
    fun findCompletedInDb(key: String): CachedResponse? {
        val record = repository.findByIdempotencyKey(key) ?: return null
        if (record.status != IdempotencyStatus.COMPLETED) return null
        val body = record.responseBody ?: return null
        val status = record.responseStatus ?: 200
        redissonClient.getBucket<String>(redisKey(key)).set("$status|$body", redisTtl)
        return CachedResponse(status, body)
    }

    private fun redisKey(key: String) = "idempotency:$key"

    private fun parse(cached: String): CachedResponse {
        val i = cached.indexOf('|')
        if (i == -1) return CachedResponse(200, cached)
        val status = cached.substring(0, i).toIntOrNull() ?: 200
        return CachedResponse(status, cached.substring(i + 1))
    }
}
