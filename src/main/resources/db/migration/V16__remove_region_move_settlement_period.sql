-- 커머스 pivot Phase 4b(PR3): region(지자체) 도메인 제거.
-- 정산 주기(settlementPeriod)를 RegionPolicy → Seller로 이동한다.

-- 1) sellers에 settlement_period 컬럼 추가(백필 위해 임시 nullable).
alter table sellers
    add column settlement_period enum ('DAILY','WEEKLY','MONTHLY') null;

-- 2) 기존 판매자의 정산주기를 소속 region에서 백필.
update sellers s
    join regions r on s.region_id = r.id
    set s.settlement_period = r.settlement_period;

-- 3) 백필 후 not null 확정.
alter table sellers
    modify column settlement_period enum ('DAILY','WEEKLY','MONTHLY') not null;

-- 4) region_id FK/컬럼 제거 후 regions 테이블 폐기.
alter table sellers drop foreign key FK8r8uqolikhj01g0f3s4xqs6sa;
alter table sellers drop column region_id;
drop table regions;
