-- 미사용(dead) 상품 상태 값 정리 — 품절은 SKU/재고(stocks) 레벨에서 관리하고 주문 시 OUT_OF_STOCK로 처리하므로
-- 상품 단위 SOLD_OUT 상태는 불필요하다(대입/조회 코드 없음, 데이터 0건).
alter table products
    modify column status enum ('DRAFT','ON_SALE','SUSPENDED') not null;
