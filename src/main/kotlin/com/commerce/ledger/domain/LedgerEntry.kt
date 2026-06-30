package com.commerce.ledger.domain

import jakarta.persistence.*
import org.hibernate.annotations.Immutable
import java.math.BigDecimal
import java.time.LocalDateTime

@Entity
@Immutable
@Table(
    name = "ledger_entries",
    indexes = [
        Index(name = "idx_ledger_tx", columnList = "transactionId"),
        Index(name = "idx_ledger_account", columnList = "account, createdAt"),
    ]
)
class LedgerEntry(
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    val account: AccountCode,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    val side: LedgerEntrySide,

    @Column(nullable = false, precision = 15, scale = 2)
    val amount: BigDecimal,

    @Column(nullable = false)
    val transactionId: Long,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    val entryType: LedgerEntryType,

    @Column(nullable = false)
    val createdAt: LocalDateTime = LocalDateTime.now(),
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0L

    init {
        require(amount > BigDecimal.ZERO) { "Amount must be positive" }
    }
}
