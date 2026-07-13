package com.commerce.transaction.domain

enum class TransactionStatus {
    PENDING, COMPLETED
}

enum class TransactionType {
    ORDER_PAYMENT, ORDER_CANCEL, SETTLEMENT,
}
