package com.commerce.voucher.domain

enum class VoucherStatus {
    ACTIVE,
    PARTIALLY_USED,
    EXHAUSTED,
    EXPIRED,
    REFUND_REQUESTED,
    REFUNDED,
    WITHDRAWAL_REQUESTED,
    WITHDRAWN,
}
