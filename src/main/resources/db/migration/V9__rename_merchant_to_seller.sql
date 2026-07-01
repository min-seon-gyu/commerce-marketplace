-- 판매자(seller) 리브랜딩: merchant → seller 도메인 개명에 맞춘 스키마 정합.
-- (ledger AccountCode의 MERCHANT_RECEIVABLE은 커머스 계정 재정의 단계에서 별도로 다룬다 — 여기선 건드리지 않는다.)

-- members.role: MERCHANT_OWNER → SELLER. 데이터 유무와 무관하게 안전한 3-step.
alter table members modify role enum ('USER','MERCHANT_OWNER','SELLER','ADMIN') not null; -- 신값 추가
update members set role = 'SELLER' where role = 'MERCHANT_OWNER';                         -- 기존행 이전
alter table members modify role enum ('USER','SELLER','ADMIN') not null;                  -- 구값 제거

-- merchants 테이블 → sellers (FK owner_id/region_id는 보존됨).
rename table merchants to sellers;

-- 판매자 식별 컬럼 개명: settlements/transactions.merchant_id → seller_id.
-- 관련 인덱스/유니크 제약(uk_settlement_period, idx_tx_merchant_period)의 컬럼 참조는 컬럼 개명 시 자동 갱신된다.
alter table settlements rename column merchant_id to seller_id;
alter table transactions rename column merchant_id to seller_id;
