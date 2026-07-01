package com.commerce.transaction.domain

enum class TransactionStatus {
    PENDING, COMPLETED, FAILED, CANCEL_REQUESTED, CANCELLED
}

enum class TransactionType {
    ORDER_PAYMENT, ORDER_CANCEL, SETTLEMENT,
}
