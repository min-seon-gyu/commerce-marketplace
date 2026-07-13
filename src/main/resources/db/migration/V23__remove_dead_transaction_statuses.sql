-- 미사용(dead) 거래 상태 값 정리 — 실제 흐름은 PENDING → COMPLETED만 사용한다.
-- 실패는 단일 트랜잭션 롤백으로, 취소는 원거래를 가리키는 별도 역거래(ORDER_CANCEL)로 처리하므로
-- FAILED / CANCEL_REQUESTED / CANCELLED 상태는 필요 없다(전이 코드 없음, 데이터 0건).
alter table transactions
    modify column status enum ('PENDING','COMPLETED') not null;
