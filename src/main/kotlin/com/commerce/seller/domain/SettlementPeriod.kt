package com.commerce.seller.domain

/** 판매자 정산 주기. (구 RegionPolicy에서 seller로 이동 — 지역 개념 제거) */
enum class SettlementPeriod {
    DAILY, WEEKLY, MONTHLY
}
