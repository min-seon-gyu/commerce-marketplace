package com.commerce.promotion.domain

enum class CouponStatus {
    ISSUED,    // 발급됨 (사용 가능)
    RESERVED,  // 예약됨 (STRETCH 비동기 예약 흐름 전용; MUST 동기 흐름은 ISSUED→REDEEMED 직행)
    REDEEMED,  // 사용 완료
    EXPIRED,   // 만료
    CANCELLED, // 취소(결제 취소로 회수)
}
