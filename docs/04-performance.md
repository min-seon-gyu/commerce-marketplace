# 04. 성능 — 쿼리 최적화 실증

성능 개선을 **재현 가능한 방식으로 실증**한다. 부하 수치를 지어내지 않고, 각 최적화를 통합 테스트로 검증한다:
- **N+1**은 실제 실행된 SQL 문 수를 세어 카트 크기와 무관하게 상수임을 단언한다.
- **인덱스**는 **동일 데이터**에 인덱스 사용 경로 vs `IGNORE INDEX`(강제 풀스캔)의 실행 시간을 비교하고, `EXPLAIN`으로 인덱스가 실제로 어떤 컬럼을 타는지 확증한다. 결과값이 동일함도 함께 단언한다.

> 측정 환경: Testcontainers MySQL 8 (통합 테스트). 아래 배수(×)는 특정 머신에서 관측된 값으로 **절대치가 아니라 재현 가능한 상대 비교**다 — 각 테스트를 실행하면 로그(`[BENCH]...`)로 재현된다.

---

## 1) 체크아웃 fan-out N+1 제거

**문제.** `OrderService.placeOrder`는 카트 항목마다 `findById(sku)` + `findById(product)`를 호출해 항목이 N개면 **2N개의 SELECT**가 나갔다(fan-out N+1).

**해결.** 항목의 SKU id를 모아 `findAllById`(IN 조회) 1건, 그 SKU들의 productId로 `findAllById` 1건 — **총 2건**으로 일괄 로드하고 맵으로 조립한다. 검증 순서(미존재 → `ENTITY_NOT_FOUND`, 판매중 아님 → `PRODUCT_NOT_ON_SALE`)는 그대로 보존한다.

**실증** (`CheckoutQueryCountTest`). Hibernate `StatementInspector`로 `skus`/`products` 대상 SELECT 문을 센다.

| 카트 항목 수 | skus SELECT | products SELECT |
|:---:|:---:|:---:|
| 개선 전 | N | N |
| 3 (개선 후) | **1** | **1** |
| 6 (개선 후) | **1** | **1** |

→ 카트 크기에 비례하지 않고 **상수(각 1건)**. (그 외 재고 차감·라인 저장은 본질적으로 항목당 1건이라 유지된다.)

---

## 2) 쿠폰 1인 사용한도 카운트 — 복합 인덱스

**문제.** 체크아웃 쿠폰 검증의 `countByMemberIdAndPromotionIdAndStatus(member, promotion, REDEEMED)`가 기존 `idx_coupon_member(member_id, status)`만으로는 회원의 `(member, status)` 전체를 훑고 `promotion_id`를 행에서 필터한다. 회원이 여러 프로모션 쿠폰을 가질수록 불필요한 행을 많이 읽는다.

**해결** (V21). 복합 인덱스 `idx_coupon_member_promo_status(member_id, promotion_id, status)`로 세 조건을 인덱스만으로 좁힌다.

**실증** (`IndexBenchmarkTest`). 회원 1명에 쿠폰 20,000건(프로모션 500종 분산)을 시드하고, 같은 데이터에서 복합 인덱스 경로 vs 강제 풀스캔을 측정. `EXPLAIN`으로 인덱스가 `member_id·promotion_id·status`를 모두 사용함을 확인.

| 경로 | 관측 시간 |
|---|---:|
| 복합 인덱스 | ~1.0 ms |
| 강제 풀스캔(`IGNORE INDEX`) | ~5.5 ms |
| **개선** | **약 ×5.6** |

---

## 3) 판매자 정산 매출 합산 — 커버링 인덱스

**문제.** 정산 배치의 `sumSellerSalesInPeriod`는 `order_lines`를 `seller_id`로 훑어 `orders`와 조인하고 `line_amount`를 합산한다. 판매자별 라인이 많고 다른 판매자 라인과 섞여 있으면, 인덱스 없이는 테이블 전체를 스캔하며 각 행의 힙(row)까지 읽는다.

**해결** (V21). 커버링 인덱스 `idx_orderline_seller_refund(seller_id, refunded, order_id, line_amount)` — 필터(`seller_id`, `refunded`)·조인 키(`order_id`)·합산 대상(`line_amount`)을 모두 인덱스에 담아, 대상 판매자 라인만 **인덱스만으로(Using index)** 읽는다(힙 접근 제거).

**실증** (`IndexBenchmarkTest`). `order_lines` 20만 건(대상 판매자는 1/10=2만 건, 나머지는 다른 판매자)을 시드하고 대상 판매자 매출 합산을 측정. `EXPLAIN`으로 `Using index`(인덱스만 처리)를 확인.

| 경로 | 관측 시간 |
|---|---:|
| 커버링 인덱스(Using index, 2만 행) | ~6 ms |
| 강제 풀스캔(`IGNORE INDEX`, 20만 행) | ~52 ms |
| **개선** | **약 ×8.4** |

---

## 방법론 · 한계

- **동일 데이터, 인덱스만 토글.** `FORCE INDEX` / `IGNORE INDEX`로 같은 행 집합에 대해 인덱스 사용 여부만 바꿔 비교했다 → 스키마/데이터 차이가 아닌 **인덱스 자체의 효과**만 격리된다.
- **정확성 동시 단언.** 두 경로의 결과값이 같음을 함께 단언해, "빠른데 틀린" 최적화를 배제한다.
- **시간은 로그, 단언은 구조.** 실행 시간은 머신 의존이라 로그로만 남기고(플레이키 방지), 테스트가 강제 실패시키는 단언은 결정적인 것(SELECT 문 수, `EXPLAIN` 인덱스 사용, 결과값 일치)에 둔다.
- **범위.** 이 문서는 **쿼리/인덱스 레벨** 실증이다. 엔드투엔드 처리량(k6 부하 시나리오)은 인프라 기동이 필요해 별도 트랙으로 둔다.
