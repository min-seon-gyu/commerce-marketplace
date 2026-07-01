-- 배송 + 반품 클레임(라인 단위). 배송완료(DELIVERED)가 반품 요청 게이트.

create table shipments (
    id              bigint      not null auto_increment,
    order_id        bigint      not null,
    status          enum ('PREPARING','SHIPPED','DELIVERED') not null,
    courier         varchar(50),
    tracking_number varchar(100),
    shipped_at      datetime(6),
    delivered_at    datetime(6),
    created_at      datetime(6) not null,
    updated_at      datetime(6) not null,
    version         bigint      not null,
    primary key (id),
    constraint uq_shipment_order unique (order_id)
) engine=InnoDB;

create table return_claims (
    id         bigint      not null auto_increment,
    order_id   bigint      not null,
    member_id  bigint      not null,
    reason     enum ('CHANGED_MIND','DEFECTIVE','WRONG_DELIVERY','OTHER') not null,
    detail     varchar(500),
    status     enum ('REQUESTED','COMPLETED','REJECTED') not null,
    created_at datetime(6) not null,
    updated_at datetime(6) not null,
    version    bigint      not null,
    primary key (id)
) engine=InnoDB;

create table return_claim_lines (
    id            bigint      not null auto_increment,
    claim_id      bigint      not null,
    order_line_id bigint      not null,
    created_at    datetime(6) not null,
    updated_at    datetime(6) not null,
    version       bigint      not null,
    primary key (id)
) engine=InnoDB;

create index idx_return_claim_order on return_claims (order_id);
create index idx_return_claim_member on return_claims (member_id, status);
create index idx_return_claim_line_claim on return_claim_lines (claim_id);
