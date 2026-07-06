package com.commerce.common.idempotency

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.LocalDateTime

interface IdempotencyRepository : JpaRepository<IdempotencyKey, Long> {
    fun findByIdempotencyKey(key: String): IdempotencyKey?
    fun deleteByIdempotencyKey(key: String)

    /** [cutoff] 이전에 생성된 미완료(IN_PROGRESS) 고아 행을 일괄 삭제하고 삭제 건수를 반환한다. */
    @Modifying
    @Query("delete from IdempotencyKey k where k.status = :status and k.createdAt < :cutoff")
    fun deleteStaleInProgress(
        @Param("status") status: IdempotencyStatus,
        @Param("cutoff") cutoff: LocalDateTime,
    ): Int
}
