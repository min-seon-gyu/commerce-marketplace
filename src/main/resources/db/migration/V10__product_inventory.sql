-- 커머스 pivot Phase 2: 상품(카탈로그) + SKU(옵션·가격) + 재고.
-- 장바구니/주문 라인·재고 차감은 SKU 단위로 이뤄진다.

create table products (
    id          bigint       not null auto_increment,
    seller_id   bigint       not null,
    name        varchar(200) not null,
    description varchar(1000),
    category    enum ('FURNITURE','LIGHTING','KITCHEN','FABRIC','DECO','APPLIANCE','OTHER') not null,
    status      enum ('DRAFT','ON_SALE','SUSPENDED','SOLD_OUT') not null,
    created_at  datetime(6)  not null,
    updated_at  datetime(6)  not null,
    version     bigint       not null,
    primary key (id)
) engine=InnoDB;

create table skus (
    id          bigint       not null auto_increment,
    product_id  bigint       not null,
    sku_code    varchar(40)  not null,
    option_name varchar(200) not null,
    options     json         not null,
    price       decimal(38,2) not null,
    created_at  datetime(6)  not null,
    updated_at  datetime(6)  not null,
    version     bigint       not null,
    primary key (id),
    constraint uk_sku_code unique (sku_code)
) engine=InnoDB;

create table stocks (
    id         bigint      not null auto_increment,
    sku_id     bigint      not null,
    quantity   int         not null,
    created_at datetime(6) not null,
    updated_at datetime(6) not null,
    version    bigint      not null,
    primary key (id),
    constraint uk_stock_sku unique (sku_id)
) engine=InnoDB;

create index idx_product_seller_status on products (seller_id, status);
create index idx_sku_product on skus (product_id);

alter table skus
    add constraint fk_sku_product foreign key (product_id) references products (id);
