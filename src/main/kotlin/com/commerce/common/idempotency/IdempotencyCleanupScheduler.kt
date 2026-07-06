package com.commerce.common.idempotency

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Duration

/**
 * 고아 IN_PROGRESS 멱등키 정리 스케줄러.
 *
 * 요청 처리 중 프로세스가 죽으면 [IdempotencyInterceptor.afterCompletion]이 실행되지 않아 선점 행이
 * 영구히 남고, 같은 키로 재시도하면 계속 409로 거절된다(poison key). 이를 주기적으로 회수한다.
 * 삭제는 멱등적이므로(같은 행을 두 번 지워도 무해) 다중 인스턴스에서도 분산 락 없이 안전하다.
 */
@Component
class IdempotencyCleanupScheduler(
    private val store: IdempotencyStore,
    @Value("\${idempotency.in-progress-ttl-minutes:60}") private val inProgressTtlMinutes: Long,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelayString = "\${idempotency.cleanup-ms:600000}")
    fun purge() {
        val deleted = store.purgeStaleInProgress(Duration.ofMinutes(inProgressTtlMinutes))
        if (deleted > 0) log.info("Purged {} stale IN_PROGRESS idempotency keys", deleted)
    }
}
