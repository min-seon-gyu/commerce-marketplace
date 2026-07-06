package com.commerce.integration

import com.commerce.common.idempotency.IdempotencyKey
import com.commerce.common.idempotency.IdempotencyRepository
import com.commerce.common.idempotency.IdempotencyStore
import com.commerce.support.IntegrationTestSupport
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.time.Duration
import java.time.LocalDateTime
import java.util.UUID

/**
 * 고아 IN_PROGRESS 멱등키 청소가 오래된 미완료 행만 회수하고,
 * 최근 미완료 행과 COMPLETED 행(멱등 보장의 원천)은 보존하는지 검증한다.
 */
class IdempotencyCleanupTest : IntegrationTestSupport() {

    @Autowired lateinit var store: IdempotencyStore
    @Autowired lateinit var repository: IdempotencyRepository

    @Test
    fun `purge removes stale IN_PROGRESS but keeps recent and COMPLETED rows`() {
        val staleKey = "stale-${UUID.randomUUID()}"
        val recentKey = "recent-${UUID.randomUUID()}"
        val completedKey = "done-${UUID.randomUUID()}"

        repository.save(IdempotencyKey(idempotencyKey = staleKey, createdAt = LocalDateTime.now().minusHours(2)))
        repository.save(IdempotencyKey(idempotencyKey = recentKey, createdAt = LocalDateTime.now()))
        repository.save(
            IdempotencyKey(idempotencyKey = completedKey, createdAt = LocalDateTime.now().minusHours(2))
                .apply { complete("{}", 200) }
        )

        store.purgeStaleInProgress(Duration.ofHours(1))

        repository.findByIdempotencyKey(staleKey) shouldBe null                    // 오래된 미완료 → 삭제
        (repository.findByIdempotencyKey(recentKey) != null) shouldBe true         // 최근 미완료 → 보존
        (repository.findByIdempotencyKey(completedKey) != null) shouldBe true      // 완료 → 보존
    }
}
