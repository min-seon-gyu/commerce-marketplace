-- 미사용(구 도메인) enum 값과 컬럼 정리 — 커머스 활성 값만 남긴다.
alter table ledger_entries
    modify column account enum (
        'CUSTOMER_CASH','SELLER_PAYABLE','SETTLEMENT_PAYABLE','POINT_BALANCE','POINT_FUNDING','PROMOTION_FUNDING'
    ) not null;

alter table ledger_entries
    modify column entry_type enum (
        'ORDER_PAYMENT','ORDER_CANCEL','POINT_EARN','COUPON_SUBSIDY','SETTLEMENT','CANCELLATION','MANUAL_ADJUSTMENT'
    ) not null;

alter table transactions
    modify column type enum ('ORDER_PAYMENT','ORDER_CANCEL','SETTLEMENT') not null;

-- 미사용 voucher_id 컬럼/인덱스 제거.
alter table transactions drop index idx_tx_voucher;
alter table transactions drop column voucher_id;
