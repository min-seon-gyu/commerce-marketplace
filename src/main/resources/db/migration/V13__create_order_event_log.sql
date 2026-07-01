-- 주문 이벤트 로그(읽기 프로젝션). Kafka 소비자가 멱등하게 기록하는 append-only 이력.
create table order_event_log (
    id           bigint       not null auto_increment,
    event_id     varchar(36)  not null,
    event_type   varchar(50)  not null,
    order_id     bigint       not null,
    member_id    bigint       not null,
    total_amount decimal(38,2) not null,
    created_at   datetime(6)  not null,
    primary key (id),
    constraint uk_oel_event_id unique (event_id)
) engine=InnoDB;

create index idx_oel_event_type on order_event_log (event_type, created_at);
create index idx_oel_order on order_event_log (order_id, created_at);
