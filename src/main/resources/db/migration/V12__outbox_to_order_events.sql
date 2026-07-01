-- 커머스 pivot Phase 4b(PR1): 감사 Outbox/Kafka 파이프라인을 주문 이벤트 전달로 이관.
-- 기존 outbox_events(감사용) → order_outbox_events(주문 이벤트용). severity 컬럼 제거(주문 이벤트는 등급 없음).

rename table outbox_events to order_outbox_events;
alter table order_outbox_events drop column severity;
alter table order_outbox_events rename index idx_outbox_unpublished to idx_order_outbox_unpublished;
