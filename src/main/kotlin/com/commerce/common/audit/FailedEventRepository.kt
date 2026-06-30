package com.commerce.common.audit

import org.springframework.data.jpa.repository.JpaRepository

interface FailedEventRepository : JpaRepository<FailedEvent, Long> {
    fun findByResolvedFalseAndRetryCountLessThan(maxRetry: Int): List<FailedEvent>
}
