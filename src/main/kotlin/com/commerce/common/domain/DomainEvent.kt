package com.commerce.common.domain

import java.time.LocalDateTime
import java.util.UUID

abstract class DomainEvent(
    val eventId: String = UUID.randomUUID().toString(),
    val occurredAt: LocalDateTime = LocalDateTime.now()
) {
    abstract val aggregateType: String
    abstract val aggregateId: Long
    abstract val eventType: String

    /** 감사 로그용 이전 상태 (서브클래스에서 오버라이드) */
    open val previousState: String? = null
    /** 감사 로그용 현재 상태 (서브클래스에서 오버라이드) */
    open val currentState: String? = null
}
