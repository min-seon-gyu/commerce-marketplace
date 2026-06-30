create table promotions (
    id bigint not null auto_increment,
    created_at datetime(6) not null,
    updated_at datetime(6) not null,
    version bigint not null,
    name varchar(100) not null,
    discount_type enum ('FIXED','PERCENTAGE') not null,
    discount_value decimal(38,2) not null,
    min_spend decimal(38,2) not null,
    per_member_limit integer not null,
    budget_limit decimal(38,2) not null,
    starts_at datetime(6) not null,
    ends_at datetime(6) not null,
    status enum ('DRAFT','ACTIVE','PAUSED','ENDED') not null,
    stackable bit not null,
    primary key (id)
) engine=InnoDB;

create table coupons (
    id bigint not null auto_increment,
    created_at datetime(6) not null,
    updated_at datetime(6) not null,
    version bigint not null,
    promotion_id bigint not null,
    member_id bigint not null,
    expires_at datetime(6) not null,
    status enum ('ISSUED','RESERVED','REDEEMED','EXPIRED','CANCELLED') not null,
    primary key (id)
) engine=InnoDB;

create table coupon_redemptions (
    id bigint not null auto_increment,
    created_at datetime(6) not null,
    updated_at datetime(6) not null,
    version bigint not null,
    coupon_id bigint not null,
    promotion_id bigint not null,
    member_id bigint not null,
    voucher_id bigint not null,
    transaction_id bigint not null,
    order_total decimal(38,2) not null,
    discount_amount decimal(38,2) not null,
    voucher_charged decimal(38,2) not null,
    cancelled bit not null,
    primary key (id)
) engine=InnoDB;

create index idx_promotion_status on promotions (status, starts_at, ends_at);
create index idx_coupon_member on coupons (member_id, status);
create index idx_coupon_promotion on coupons (promotion_id);
create index idx_couponredemption_tx on coupon_redemptions (transaction_id);
create index idx_couponredemption_member_promo on coupon_redemptions (member_id, promotion_id, cancelled);
