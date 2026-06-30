package com.commerce.common.idempotency

import jakarta.persistence.*
import java.time.LocalDateTime

enum class IdempotencyStatus { IN_PROGRESS, COMPLETED }

@Entity
@Table(
    name = "idempotency_keys",
    indexes = [Index(name = "idx_idem_key", columnList = "idempotencyKey", unique = true)]
)
class IdempotencyKey(
    @Column(nullable = false, unique = true, length = 64)
    val idempotencyKey: String,

    @Column(columnDefinition = "TEXT")
    var responseBody: String? = null,

    var responseStatus: Int? = null,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var status: IdempotencyStatus = IdempotencyStatus.IN_PROGRESS,

    @Column(nullable = false)
    val createdAt: LocalDateTime = LocalDateTime.now()
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0L

    fun complete(responseBody: String, responseStatus: Int) {
        this.responseBody = responseBody
        this.responseStatus = responseStatus
        this.status = IdempotencyStatus.COMPLETED
    }
}
