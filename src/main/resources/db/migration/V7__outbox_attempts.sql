-- outbox 전달 실패 재시도 횟수. 최대 시도 초과 행을 격리(quarantine)해 poison 무한 재처리/head-of-line 블로킹을 막고,
-- reconciliation 스윕이 영구 실패 행을 재처리 대상에서 제외하도록 한다.
alter table outbox_events
    add column attempts int not null default 0;
