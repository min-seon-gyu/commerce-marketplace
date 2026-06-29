package com.commerce.common.audit

import jakarta.persistence.*
import java.time.LocalDateTime

@Entity
@Table(name = "failed_events")
class FailedEvent(
    @Column(nullable = false, length = 36)
    val eventId: String,

    @Column(nullable = false, length = 50)
    val eventType: String,

    @Column(nullable = false, columnDefinition = "TEXT")
    val payload: String,

    @Column(nullable = false, columnDefinition = "TEXT")
    val errorMessage: String,

    var retryCount: Int = 0,

    var resolved: Boolean = false,

    @Column(nullable = false)
    val createdAt: LocalDateTime = LocalDateTime.now()
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0L
}
