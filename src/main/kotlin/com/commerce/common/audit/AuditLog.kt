package com.commerce.common.audit

import jakarta.persistence.*
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.LocalDateTime

@Entity
@Table(
    name = "audit_logs",
    indexes = [
        Index(name = "idx_audit_aggregate", columnList = "aggregateType, aggregateId, createdAt"),
        Index(name = "idx_audit_event_type", columnList = "eventType, createdAt"),
        Index(name = "idx_audit_actor", columnList = "actorId, createdAt")
    ]
)
class AuditLog(
    @Column(nullable = false, unique = true, length = 36)
    val eventId: String,

    @Column(nullable = false, length = 50)
    val eventType: String,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    val severity: AuditSeverity,

    @Column(nullable = false, length = 30)
    val aggregateType: String,

    @Column(nullable = false)
    val aggregateId: Long,

    val actorId: Long? = null,

    @Column(length = 20)
    val actorType: String? = null,

    @Column(nullable = false, length = 50)
    val action: String,

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "json")
    val previousState: String? = null,

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "json")
    val currentState: String? = null,

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "json")
    val metadata: String? = null,

    @Column(length = 64)
    val idempotencyKey: String? = null,

    @Column(nullable = false, columnDefinition = "DATETIME(6)")
    val createdAt: LocalDateTime = LocalDateTime.now()
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0L
}
