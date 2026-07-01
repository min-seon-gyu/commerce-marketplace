package com.commerce.ledger.domain

enum class AccountCode(val description: String) {
    CUSTOMER_CASH("고객 결제 현금"),       // 차변정상: 고객이 지불한 현금 유입
    SELLER_PAYABLE("판매자 미지급금"),     // 대변정상: 플랫폼이 판매자에게 지급할 정산액(gross)
    SETTLEMENT_PAYABLE("정산 미지급금"),   // 대변정상: 정산 확정으로 지급이 확정된 금액
    POINT_BALANCE("포인트 잔액"),          // 차변정상: 적립 포인트 잔액
    POINT_FUNDING("포인트 출연금"),        // 대변정상: 플랫폼 포인트 적립 출연
    PROMOTION_FUNDING("프로모션 출연금"),  // 대변정상: 플랫폼 쿠폰/프로모션 출연
}
