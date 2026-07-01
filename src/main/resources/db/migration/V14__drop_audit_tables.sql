-- 감사 도메인 제거: audit_logs(감사 로그), failed_events(구 재처리 스토어 — 이미 코드 미참조) 폐기.
drop table if exists audit_logs;
drop table if exists failed_events;
