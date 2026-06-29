package com.commerce.ledger.domain

enum class AccountCode(val description: String) {
    MEMBER_CASH("회원 현금"),
    VOUCHER_BALANCE("상품권 잔액"),
    MERCHANT_RECEIVABLE("가맹점 미수금"),
    REVENUE_DISCOUNT("할인 수익"),
    EXPIRED_VOUCHER("만료 상품권"),
    REFUND_PAYABLE("환불 미지급금"),
    SETTLEMENT_PAYABLE("정산 미지급금"),
}
