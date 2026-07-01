package com.commerce.transaction.infrastructure

import com.commerce.transaction.domain.Transaction
import org.springframework.data.jpa.repository.JpaRepository

interface TransactionJpaRepository : JpaRepository<Transaction, Long>
