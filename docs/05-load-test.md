# 05. 부하테스트 — 상품 상세 API 캐시 적용 (before / after)

read-heavy 상품 상세 API에 **Redis 캐시-어사이드**를 적용하고, k6로 **캐시 전/후 처리량·지연을 실측 비교**한다. 절대 RPS는 측정 머신에 의존하므로, **개선폭(before→after)** 과 **재현 방법**에 의미를 둔다.

## 대상 · 환경

- **엔드포인트**: `GET /api/v1/products/{id}` — 상품 1건 + SKU 목록 + 재고(배치 조회). 캐시 전에는 **매 요청 DB 왕복(요청당 3쿼리)**.
- **최적화**: Redis 캐시-어사이드(TTL 30s), 상태 변경(`on-sale`) 시 무효화 — [`ProductController.getDetail`](../src/main/kotlin/com/commerce/product/interfaces/ProductController.kt)
- **구성**: 로컬 단일 인스턴스 + MySQL + Redis(Kafka off), 상품 2,000개 시드.
- **부하**: k6(docker `grafana/k6`) `ramping-vus`, 동시 100, 70초 — [`load-test/product-detail.js`](../load-test/product-detail.js)

## 결과 (동시 100 VU, 70초)

| 지표 | before (캐시 없음) | after (Redis 캐시) | 개선 |
|---|:---:|:---:|:---:|
| **처리량** | 772 RPS | **1,856 RPS** | **≈ 2.4배** |
| p50 | 95ms | 31ms | |
| p90 | 137ms | 58ms | |
| **p95** | 157ms | **73ms** | **≈ 2.1배 ↓** |
| p99 | 218ms | 290ms\* | |
| 에러율 | 0.02% | 0.00% | |

<sub>\* after의 p99는 부하 초반 **콜드 캐시(첫 접근 미스)** 구간의 꼬리를 포함한다. 캐시가 데워진 뒤 정상 구간은 p95(73ms) 수준.</sub>

## 해석

- 캐시-어사이드로 대부분의 요청이 **DB 3쿼리를 건너뛰어**, 같은 부하에서 **처리량 2.4배·p95 2.1배** 개선.
- **정합성 트레이드오프**: 재고는 TTL(30s) 내 지연될 수 있으나 **카탈로그 열람엔 무방**하고, **정확한 재고는 체크아웃 시 분산 락 + `SELECT FOR UPDATE`로 강제**한다(캐시하지 않음). 상품 상태 변경(`on-sale`) 시엔 즉시 무효화.
- p99 꼬리는 **콜드 캐시 워밍**(부하 시작 직후 첫 접근 미스)에서 발생 — 사전 워밍하면 사라진다.

## 참고: 캐시 전 과부하 거동 (graceful degradation)

캐시 없이 부하를 3배(300 VU)로 올린 경우 — 처리량은 ~700 RPS로 포화하고 지연은 오르지만(p95 780ms) **에러 0%**, 타임아웃·크래시 없이 버틴다. 즉 포화 이후에도 요청을 떨어뜨리지 않고 지연으로 흡수한다.

## 재현

```bash
# 1. 인프라 (MySQL 포트 충돌 시 MYSQL_PORT로 변경)
docker compose up -d mysql redis
# 2. 앱 (Kafka 불필요 → 끄고 기동, DB 포트 맞춤)
DB_PORT=3306 ORDER_KAFKA_ENABLED=false ./gradlew bootRun
# 3. 상품 2,000개 시드
docker exec -i <mysql> mysql -uroot -proot voucher <<'SQL'
SET SESSION cte_max_recursion_depth = 1000000;
INSERT INTO products (seller_id,name,description,category,status,created_at,updated_at,version)
WITH RECURSIVE seq(n) AS (SELECT 1 UNION ALL SELECT n+1 FROM seq WHERE n < 2000)
SELECT 1, CONCAT('상품 ', n), '부하테스트용', 'OTHER', 'ON_SALE', NOW(6), NOW(6), 0 FROM seq;
INSERT INTO skus (product_id,sku_code,option_name,options,price,created_at,updated_at,version)
SELECT id, CONCAT('SKU-', id), '기본', '{}', 10000+id, NOW(6), NOW(6), 0 FROM products;
INSERT INTO stocks (sku_id,quantity,created_at,updated_at,version)
SELECT id, 100000, NOW(6), NOW(6), 0 FROM skus;
SQL
# 4. 부하테스트 (before/after는 캐시 코드 유무로 비교)
docker run --rm -i --add-host=host.docker.internal:host-gateway grafana/k6 run - < load-test/product-detail.js
```

## 한계

- 로컬 단일 머신 절대치라 RPS 자체는 하드웨어 의존 — **개선폭(2.4배·2.1배)** 에 의미.
- 다음: 쓰기(체크아웃) throughput 측정, 캐시 사전 워밍 후 정상구간 p99.
