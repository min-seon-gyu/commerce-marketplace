# 개선 작업 트래커 (Improvement Tracker)

> **이 문서의 목적**: 전체 코드 리뷰(6개 영역, 15개 관점)에서 도출하고 실제 코드로 재검증한 개선 항목을,
> 여러 세션/여러 주에 걸쳐 진행하기 위한 **단일 진행 상황 공유 파일**이다.
> 작업할 때마다 아래 체크리스트와 진행 로그를 갱신한다.
>
> - 생성일: 2026-07-06
> - 최종 갱신: 2026-07-06
> - 근거: 리뷰 결과는 fork 에이전트 6종으로 도출 → 상위 14개 발견을 실제 코드(`file:line`)로 직접 재대조하여 확정.
> - 범위: 학습/포트폴리오 프로젝트이나 재무·동시성·정합성 코드는 프로덕션 기준으로 다룬다.

---

## 0. 사용법

- 상태 표기: `예정` → `진행중` → `완료` (보류는 `보류`, 결정 필요는 `결정대기`).
- 각 묶음은 하나의 브랜치/PR 단위를 지향한다(같은 파일·주제를 함께 건드리는 단위로 구성).
- 작업 시작 시 해당 묶음 상태를 `진행중`으로, 머지 후 `완료`로 바꾸고 **§7 진행 로그**에 한 줄 남긴다.
- 완료 기준(Acceptance)에 적힌 검증(테스트/수동 확인)을 통과해야 `완료`로 표기한다.

---

## 1. 요약 대시보드

| 묶음 | 제목 | 중요도(항목) | 규모 | 웨이브 | 상태 |
|------|------|-------------|------|--------|------|
| A | 보안 접근제어 | 1·2·4 (최상) | 1~2일 | 1 | ✅ 완료 (PR #29) |
| C | 멱등성 견고화 | 3·12 | 1~2일 | 1 | ✅ 완료 (PR #30) |
| D | 포인트·쿠폰 정합성 즉시 수정 | 7·9 | 1~2일 | 1 | ✅ 완료 (PR #31) |
| F | 설정·배포 안전값 | 14 | 반나절 | 1 | ✅ 완료 (PR #32) |
| B | 인증 토큰 하드닝 (핵심만) | 5·11 | 1~2일 | 2 | ✅ 완료 (PR #35) |
| E | 상품 상세 캐시 | 10 (+16 일부) | 1~2일 | 2 | ✅ 완료 (PR #33) |
| G | 프로모션 예산 신뢰성 | 8 | 3일~1주 | 2 | ✅ 완료 (PR #34) |
| H | 정산 clawback (누적 모델) ★재무 | 6 | 1주+ | 3 | 진행중 |
| I | 플랫폼 수수료 모델 | 19 (H 의존) | 1주 | 3 | 예정 |
| J | 구조 리팩터링(경계·순환) | 17·18 | 1주+ | 4 | 예정 |
| K | 운영 확장성(스케줄러·페이지네이션) | 15·18 일부 | 3일~1주 | 4 | 예정 |
| L | 테스트 인프라(커버리지·JaCoCo) | 16 | 3일~1주 | 4 | 예정 |
| M | 드리프트 정리(voucher·죽은코드) | 20 | 3일~1주 | 4 | 예정 |

> 진행률: 7 / 13 묶음 완료 (A, C, D, F, E, G, B) — 웨이브 1·2 완료, 웨이브 3 진행

---

## 2. 웨이브 실행 순서

각 웨이브는 앞 웨이브가 끝나야 하는 것은 아니며, **권장 착수 순서**다. J·K·L·M은 웨이브 4로 묶되 상시 병행 가능.

- **웨이브 1 (1주 목표) — 위험 대비 노력 최고**: `A` → `C` → `D` → `F`
  - 보안 노출 즉시 차단 + 금액 버그 즉시 수정 + 저위험 설정 quick win.
- **웨이브 2 (2주 목표)**: `B` → `E` → `G`
  - 인증 수명·캐시 가용성·예산 신뢰성.
- **웨이브 3 (3~4주 목표) — 재무 토대**: `H` → `I`
  - `H`(원장 subjectId)를 먼저 해야 clawback·수수료·검증이 그 위에 얹힌다.
  - ⚠️ `H` 착수 전까지의 임시 완화책은 §5-D 참고(markPaid 직전 재검증).
- **웨이브 4 (상시 병행)**: `J` · `K` · `L` · `M`
  - `L`의 ArchUnit/JaCoCo는 일찍 넣을수록 회귀 방지 효과가 크다.

---

## 3. 묶음별 상세

### 묶음 A — 보안 접근제어  `상태: 검증완료·커밋대기`
- **목표**: 미인증 노출(IDOR)과 기본 개방 자세를 제거한다.
- **대상 파일**: `config/SecurityConfig.kt`, `transaction/interfaces/TransactionController.kt`, `member/interfaces/MemberController.kt`, `test/.../AdminAuthorizationTest.kt`
- **할 일**:
  - [x] `SecurityConfig.kt:61` `anyRequest().permitAll()` → `authenticated()`로 반전, 공개 경로(상품 GET, 프로모션/셀러 GET, register/login, swagger, actuator)를 명시적 permitAll 목록으로 재정의.
  - [x] `GET /api/v1/transactions/{id}` 인증 + 소유권 검증(본인 `memberId` 또는 ADMIN) — `OrderController.get` 패턴 재사용. 정산 거래(memberId 없음)는 ADMIN 전용.
  - [x] `GET /api/v1/members/{id}` 본인/ADMIN 제한(A-1 결정: 공개 프로필 DTO 없음).
  - [ ] ~~actuator metrics/prometheus 관리 포트 분리~~ → **묶음 K로 이관**(Prometheus 스크레이프가 무인증 `/actuator/prometheus`에 의존하므로 관리 포트 분리가 정공법. 현재는 `/actuator/**` permitAll 유지).
  - [x] `AdminAuthorizationTest`를 엔드포인트×역할 인가 매트릭스로 확장(트랜잭션/회원 401·403·200 + 공개 GET 과잠금 회귀 12건 추가).
- **완료 기준**: 비로그인으로 `/transactions/{id}`·`/members/{id}` 접근 시 401 ✅. 매트릭스 테스트 통과 ✅. 전체 스위트 그린(회귀 없음) ✅.
- **검증(2026-07-06)**: `./gradlew test` 전체 통과(1m16s). 컴파일 exit 0.

### 묶음 C — 멱등성 견고화  `상태: 진행중`
- **목표**: 멱등키를 사용자·엔드포인트에 바인딩하고, 고아 IN_PROGRESS 행을 회수한다.
- **대상 파일**: `common/idempotency/IdempotencyInterceptor.kt`, `IdempotencyStore.kt`, `IdempotencyRepository.kt`, 신규 `IdempotencyCleanupScheduler.kt`
- **할 일**:
  - [x] 저장/조회 키를 `(memberId, method+URI, Idempotency-Key)` 복합으로 변경 — 인터셉터에서 SHA-256(64 hex)으로 해시해 기존 `idempotency_key varchar(64)` 컬럼에 그대로 저장(Redis 키도 동일 스코프). **마이그레이션 불필요**(단일 컬럼 유니크 유지, 저장 값만 스코프 해시로 변경).
  - [x] stale `IN_PROGRESS` 행 청소: `IdempotencyStore.purgeStaleInProgress(ttl)` + `IdempotencyCleanupScheduler`(`@Scheduled`, 기본 TTL 60분). COMPLETED 행은 보존.
  - [x] ~~유니크 제약 마이그레이션(V22)~~ → 불필요(위 참고).
- **완료 기준**: 서로 다른 회원이 동일 키 문자열을 보내도 각자 주문 처리(테스트 `IdempotencyScopingTest`). 오래된 IN_PROGRESS만 청소·COMPLETED 보존(테스트 `IdempotencyCleanupTest`). 기존 `IdempotencyConcurrencyTest`·`IdempotencyStatusCodeTest` 유지(동일 사용자·URL이라 영향 없음).
- **주의**: 청소 스케줄러도 다중 인스턴스 가드는 없으나 삭제가 멱등적이라 안전(분산 락 불필요). 나머지 스케줄러 가드는 묶음 K.

### 묶음 D — 포인트·쿠폰 정합성 즉시 수정  `상태: 진행중`
- **목표**: 설계 없이 바로 고칠 수 있는 금액/정합성 버그를 제거한다. (D-1 결정: 취소 시 쿠폰·예산 복원.)
- **대상 파일**: `order/application/OrderService.kt`, `point/application/PointEarnService.kt`, `promotion/domain/Coupon.kt`
- **할 일**:
  - [x] 포인트 배분 총량을 `calculateEarn(order.paidAmount)`(현재 rate) → **적립 당시 기록된 EARN 합계**(`PointEarnService.originalEarned`)로 교체. 전액취소 `reverseEarn`과 동일 소스로 일치.
  - [x] `cancelOrder` 커밋 후 `budgetManager.release` 호출 + 쿠폰 `restore()`(REDEEMED→ISSUED). 부분환불 `refundLines`는 현행대로 소진 유지(발송 후 반품 흐름).
  - [ ] ~~markPaid 직전 재검증 완화책~~ → 묶음 H(clawback)에서 본 설계로 처리.
- **완료 기준**: 부분환불 역적립이 기록 EARN에 비례(테스트 `OrderPartialRefundTest`). 전체취소 후 쿠폰 ISSUED·예산 0·재사용 가능(테스트 `OrderCouponDiscountTest`). 기존 원장 정합성 테스트 유지.
- **참고**: 부분환불(refund)과 전체취소(cancel)의 쿠폰 처리 비대칭은 의도적 — 발송 전 셀프취소는 쿠폰 반환, 발송 후 반품은 소진 유지.

### 묶음 F — 설정·배포 안전값  `상태: 진행중`
- **목표**: 검증된 튜닝과 안전한 종료를 설정에 반영(저위험 quick win).
- **대상 파일**: `src/main/resources/application.yml`, `src/test/resources/application-test.yml`, `config/RedisConfig.kt`
- **할 일**:
  - [x] HikariCP `maximum-pool-size: ${DB_POOL_SIZE:40}` + `connection-timeout: 10000` + `max-lifetime: 1800000` 명시(RESULTS.md 검증값). 테스트 프로파일은 풀 10으로 오버라이드(컨테이너 커넥션 압박 회피).
  - [x] `server.shutdown: graceful` + `spring.lifecycle.timeout-per-shutdown-phase: 30s`(Dockerfile exec form 주석과 실제 동작 일치).
  - [x] Redisson `connectTimeout`/`timeout`/`retryAttempts`/`retryInterval` 명시(장애 시 예측 가능한 실패).
- **완료 기준**: 앱 정상 기동 + 전체 스위트 그린(설정 유효성·회귀 없음). 실제 처리량 개선은 RESULTS.md에 기 문서화.
- **참고**: actuator 노출 제한은 F 범위에서 제외(관리 포트 분리 = 묶음 K). 처리량 효과는 config 레벨이라 통합 테스트로 재현하지 않고 부팅+기존 부하테스트로 검증.

### 묶음 B — 인증 토큰 하드닝 (핵심만)  `상태: 진행중`
- **결정(2026-07-06)**: "핵심만" 범위 — dev 시크릿 가드 반전 + 정지/탈퇴 Redis 블랙리스트. 리프레시 토큰·만료 단축은 **후속(별도 묶음)**으로 남김.
- **대상 파일**: `config/JwtTokenProvider.kt`, `JwtAuthenticationFilter.kt`, `member/application/MemberService.kt`, 신규 `member/infrastructure/MemberTokenBlacklist.kt`, 신규 `member/domain/event/MemberUnsuspendedEvent.kt`
- **할 일**:
  - [x] `JwtTokenProvider.validateSecret` 가드 반전: `local/test/dev` 프로파일일 때만 `DEV_SECRET` 폴백 허용, 그 외(프로파일 미지정 포함)엔 시크릿 없으면 기동 실패.
  - [x] 정지/탈퇴 회원 Redis 블랙리스트: `MemberTokenBlacklist`가 `MemberSuspended/Withdrawn/Unsuspended` 이벤트를 AFTER_COMMIT 리스닝해 등재/해제(기존 미사용 이벤트 활용). `JwtAuthenticationFilter`가 매 요청에서 확인. TTL=토큰 수명. Redis 장애 시 fail-open.
  - [ ] ~~토큰 만료 단축 + 리프레시 토큰~~ → 후속(범위 밖).
- **완료 기준**: 프로파일 누락+시크릿 미설정 시 기동 실패(테스트). 정지/탈퇴 회원의 기존 토큰 즉시 401, 해제 시 재유효(테스트). 전체 스위트 그린.
- **후속(별도)**: access 토큰 만료 단축(1h) + 리프레시 토큰(/refresh, 저장·회전).

### 묶음 E — 상품 상세 캐시  `상태: 진행중`
- **대상 파일**: `product/interfaces/ProductController.kt`, 신규 `product/interfaces/ProductDetailCache.kt`, 신규 `test/.../ProductDetailCacheTest.kt`
- **할 일**:
  - [x] 캐시 read/write/invalidate를 try/catch로 감싸 Redis 장애·역직렬화 실패 시 DB 폴백 + warn(패턴: `IdempotencyStore.cacheQuietly`).
  - [x] 캐시 로직을 컨트롤러 → `ProductDetailCache` 컴포넌트로 분리. 뷰 DTO(`ProductDetailResponse`) 캐싱이라 인터페이스 계층에 둠(application이 뷰 DTO에 의존하는 레이어 위반 회피). 무효화는 `onSale` 커밋 후 컨트롤러에서 호출.
  - [x] TTL 지터(25~35s) 추가.
  - [x] `ProductDetailCacheTest` 추가(put/get 왕복, evict, 깨진 JSON 폴백, 빈 캐시 miss).
- **완료 기준**: Redis 장애/역직렬화 실패 시 `get`이 null 반환(500 아님, DB 폴백). 캐시 테스트 통과. 전체 스위트 그린.
- **참고**: "무효화를 상태변경 서비스에 공배치"는 레이어 위반(app→interface)이라 이벤트 도입이 필요 → 과설계 회피 위해 현행 컨트롤러 무효화 유지. 재고 변경 시 캐시는 TTL(≤35s)로만 stale(문서화된 의도).

### 묶음 G — 프로모션 예산 신뢰성  `상태: 진행중`
- **구현 메모(2026-07-06)**: 진실원천은 `orders`(비취소 쿠폰 주문의 discount_amount 합) — 스키마의 `coupon_redemptions` 테이블은 엔티티/쓰기 없는 빈 레거시라 사용하지 않음(정리는 묶음 M). `CouponJpaRepository.sumConsumedBudgetByPromotion` 추가, `PromotionBudgetManager.resync`(SET) + `release` Lua 하한 0 클램프, `PromotionBudgetResyncScheduler`(@Scheduled, 기본 5분; 테스트는 resync-ms 크게 설정 후 수동 호출). 허위 주석(CouponRedemption/재동기화 잡) 정정. 테스트 `PromotionBudgetResyncTest`(클램프, DB 재동기화·취소 제외).

- **대상 파일**: `promotion/infrastructure/PromotionBudgetManager.kt`, 신규 재동기화 스케줄러
- **할 일**:
  - [ ] DB(`orders`의 couponId→promotion 조인, `discount_amount` 합) 기준으로 Redis 예산 카운터를 주기/기동 시 재구축하는 잡 구현.
  - [ ] Lua 스크립트/`release`에 하한 0 클램프(음수 카운터 방지).
  - [ ] `PromotionBudgetManager.kt:13`의 허위 주석(`CouponRedemption`, 재동기화 잡) 제거 또는 실제 구현으로 대체.
- **완료 기준**: Redis 키 유실 후 재동기화 잡이 DB 기준으로 카운터 복원(테스트).

### 묶음 H — 정산 clawback (누적 모델)  `상태: 진행중`  ★재무 기반작업
- **결정(2026-07-06)**: H-1 = 차기 정산 음수 조정(이월). 구현은 **누적 정산 모델**로 함 — 기간별 매출 대신 `총 미환불 매출 − 확정정산 합`을 정산액으로 계산. 확정 후 환불이 총 미환불 매출을 줄여 자동 clawback되고, 음수면 정산을 만들지 않고 자연 이월된다(원장 SELLER_PAYABLE도 0으로 수렴). 이 방식은 이월을 별도 상태 없이 처리하고 `refundedAt`·per-line 추적이 불필요.
- **대상 파일**: `seller/application/SettlementService.kt`(calculate/confirm/buildForBatch 누적화 + `cumulativeNet`), `order/infrastructure/OrderLineJpaRepository.kt`(`sumSellerNonRefundedSalesAllTime`), `seller/infrastructure/SettlementJpaRepository.kt`(`sumAmountBySellerAndStatusIn`), 신규 `test/.../SettlementClawbackTest.kt`
- **할 일**:
  - [x] 정산액 = 누적 순정산(총 미환불 매출 − 확정/지급 정산 합). calculate/confirm/buildSettlementForBatch 일관 적용. 기간 키/unique/멱등은 유지.
  - [x] 확정 후 환불이 차기 정산에서 차감(clawback)되고, 환불 > 신규매출이면 정산 미생성(이월). 테스트로 검증.
  - [ ] ~~마이그레이션(refundedAt/subjectId)~~ → 누적 모델은 스키마 변경 불필요.
- **완료 기준**: 정산 확정 후 환불 → 차기 정산에서 차감(테스트 `SettlementClawbackTest`). 이월 시 정산 미생성. 기존 정산 테스트 유지(모두 판매자당 1건이라 `owed−0=기간매출`로 무영향). 전체 스위트 그린.
- **후속(H-2, 별도)**: `LedgerEntry.subjectId`(판매자별 원장 대사) + `LedgerVerificationService` 강화(transactionId별 균형, 판매자별 SELLER_PAYABLE net≥0, SETTLEMENT_PAYABLE vs 확정 정산 합). `D`의 markPaid 재검증 완화책은 이 clawback으로 불필요.
- **후행**: `I`(수수료)가 이 위에 얹힘.

### 묶음 I — 플랫폼 수수료 모델  `상태: 예정`  (H 의존)
- **대상 파일**: `seller/domain/Seller.kt`, `ledger/domain/AccountCode.kt`, `seller/application/SettlementService.kt`, 마이그레이션
- **할 일**:
  - [ ] `Seller.commissionRate` + `COMMISSION_REVENUE` 계정 추가.
  - [ ] 정산 분개 확장: `DEBIT SELLER_PAYABLE S / CREDIT SETTLEMENT_PAYABLE (S−fee) + CREDIT COMMISSION_REVENUE fee`. 수수료 반올림은 `allocate` 패턴 재사용.
  - [ ] `docs/03-financial-design.md`에 수수료 모델 반영.
- **완료 기준**: 정산 시 수수료가 원장에 분리 기록되고 차대변 균형 유지(테스트).

### 묶음 J — 구조 리팩터링(경계·순환)  `상태: 예정`
- **대상 파일**: 도메인 간 리포지토리 직접 주입부(`OrderService.kt:21-26` 등), `docs/02-architecture-decisions.md`, 신규 ArchUnit 테스트
- **할 일**:
  - [ ] 타 도메인 접근을 application 서비스 경유로 한정(순환 2개 해소: `order→product→seller→order`, `point↔ledger`).
  - [ ] `allocate`(`OrderService.kt:305-319`)를 `ProportionalAllocator` 도메인 객체로 승격 + 단위 테스트.
  - [ ] ArchUnit로 "순환 금지 + 도메인 간 infrastructure 접근 금지" 회귀 방지.
  - [ ] 문서의 "헥사고날 4계층" 주장을 실제 구조로 정정하거나 핵심 도메인만 포트 도입.
- **완료 기준**: ArchUnit 테스트 통과. `allocate` 단독 테스트 존재.

### 묶음 K — 운영 확장성  `상태: 예정`
- **대상 파일**: `@Scheduled` 5곳(`DirectOrderEventRelay`, `KafkaOrderEventRelay`, `OrderEventReconciliationScheduler`, `SettlementBatchScheduler`, `LedgerVerificationService`), 이력성 리포지토리, `KafkaOrderEventRelay`
- **할 일**:
  - [ ] `@Scheduled` 5곳에 ShedLock(또는 Redisson 스케줄 락) 도입 — 또는 "단일 인스턴스 전제"를 README/운영 문서에 명시.
  - [ ] 이력성 조회 4곳(주문/포인트/쿠폰/셀러 라인) `Pageable` 도입.
  - [ ] `KafkaOrderEventRelay`의 건당 `send().get()` → 배치 send 후 일괄 대기.
- **완료 기준**: 2 인스턴스 기동 시 스케줄러 중복 실행 없음(또는 문서화). 이력 조회가 페이지 단위.
- **정정 메모**: 무보호 스케줄러는 4곳이 아니라 **5곳**(원장 검증 `LedgerVerificationService.kt:30` 포함).

### 묶음 L — 테스트 인프라  `상태: 예정`
- **대상 파일**: `build.gradle.kts`(JaCoCo), `test/support/`, 신규 테스트, `.github/workflows/ci.yml`
- **할 일**:
  - [ ] JaCoCo 도입 + `jacocoTestReport`를 CI에 연결(초기 가시화 → 이후 하한 게이트).
  - [ ] 커버리지 공백 보강: `CartServiceTest`, `TransactionStatusTransitionTest`, `MemberLifecycleTest`(정지/탈퇴 거부), 상태머신 순수 단위 테스트.
  - [ ] 통합 테스트 컨테이너 3세트 중복 → 공유 싱글턴으로 통합(`WebIntegrationTestSupport` 분리).
  - [ ] 동시성 테스트 예외 삼킴 → `runConcurrently(n){}` 헬퍼로 이관.
- **완료 기준**: JaCoCo 리포트 생성. cart/transaction/member 계층 테스트 존재.

### 묶음 M — 드리프트 정리  `상태: 예정 (죽은 enum/상태 2건 선행 완료)`
- **대상 파일**: `load-test/k6/*`, `monitoring/**`, `application.yml`(voucher 태그), `Dockerfile`, 죽은 이벤트/QueryDSL, `ci.yml`
- **할 일**:
  - [ ] voucher 잔재 일괄 정리: k6 시나리오 4종(제거된 `/api/v1/vouchers/*` 호출 → 404), Grafana 대시보드(존재하지 않는 메트릭), DB명·컨테이너·jar·메트릭 태그.
  - [ ] 리스너 없는 도메인 이벤트 6종(Seller/Member/Settlement) 삭제 또는 outbox 연결.
  - [ ] 미사용 QueryDSL 의존/설정 제거(kapt 빌드 비용).
  - [ ] 정산 기간 경계 `BETWEEN … LocalTime.MAX` → 반개구간(`>= start AND < endExclusive`)으로 교체.
  - [ ] CI 복붙 주석 정리, 모니터링 스택 2벌 통합.
  - [x] 죽은 enum/상태 선행 정리(대화발 단발 chore, 정식 M 실행과 별개로 처리):
    - `ProductStatus.SOLD_OUT` 제거 — 품절은 SKU/재고(stocks) 레벨 관리, 상품 단위 상태 불필요 (PR #37, `Product.kt`·`V22`).
    - `TransactionStatus` `FAILED`·`CANCEL_REQUESTED`·`CANCELLED` + `fail()`/`requestCancel()`/`cancel()` + `TRANSACTION_NOT_CANCELLABLE` 제거 — 실패=단일 tx 롤백, 취소=원거래 가리키는 별도 역거래(ORDER_CANCEL)로 처리하므로 불필요(불변·감사추적 원칙과 모순) (PR #38, `Transaction.kt`·`TransactionStatus.kt`·`ErrorCode.kt`·`V23`).
- **완료 기준**: k6 시나리오가 현 API로 실행 가능. Grafana 패널이 실제 메트릭 표시.

---

## 4. 재검증된 발견 근거 (Evidence)

> 다음은 실제 코드를 직접 읽어 확정한 근거다(재리뷰 없이 다음 세션이 바로 착수할 수 있도록 보존).

| # | 발견 | 근거 `file:line` | 판정 |
|---|------|------------------|------|
| 1 | 결제 트랜잭션 미인증 공개(IDOR) | `TransactionController.kt:35-37` + `SecurityConfig.kt:61`(매처 부재→permitAll) | ✅ Critical |
| 2 | 회원 PII 공개 조회 | `MemberController.kt:25-27`, `MemberResponse.kt:5-10`(email·name·status·role) | ✅ Critical |
| 3 | 기본 개방 자세(1·2 근본원인) | `SecurityConfig.kt:61` `anyRequest().permitAll()` | ✅ Critical |
| 4 | 정산 확정 후 환불 회수 경로 부재 | `SettlementService.kt:117` + `OrderLineJpaRepository.kt:20-34`(`refunded=false`,`created_at between`) | ✅ Critical(재무손실) |
| 5 | 멱등키 사용자 미바인딩 | `IdempotencyInterceptor.kt:40`, `IdempotencyKey.kt:14`(단일 유니크) | ✅ 조건부(§5) |
| 6 | 부분환불 포인트 역적립 왜곡 | `OrderService.kt:250`(현재 rate 재계산) | ✅ 조건부(§5) |
| 7 | 취소 시 쿠폰·예산 미복원 | grep: `release`는 `OrderService.kt:136`뿐, `cancelOrder`에 없음 / `PromotionBudgetManager.kt:12` 주석과 불일치 | ✅ Warning |
| 8 | 예산 재동기화 잡·CouponRedemption 부재 | grep: `CouponRedemption`은 `PromotionBudgetManager.kt:13` 주석에만 | ✅ Warning |
| 9 | JWT dev 시크릿 가드가 prod에만 | `JwtTokenProvider.kt:28-35`(isProd일 때만 throw) | ✅ 조건부·블라스트반경 큼 |
| 10 | 정지/탈퇴 회원 토큰 24h 유효 | `JwtAuthenticationFilter.kt:32-41`, 만료 `JwtTokenProvider.kt:16`(86400000) | ✅ Warning |
| 11 | Redis 장애 시 상품 상세 500 | `ProductController.kt:101-104`, `:91`(try/catch 없음) vs `IdempotencyStore.kt:73-78`(올바른 패턴) | ✅ Warning |
| 12 | IN_PROGRESS 멱등키 고아 | `IdempotencyStore.kt:57-60`(release에서만 삭제), 청소 스케줄러 0건 | ✅ Warning |
| 13 | HikariCP 튜닝 미반영 | grep: `application.yml`에 hikari 설정 전무 | ✅ Warning |
| 14 | 스케줄러 다중 인스턴스 가드 부재 | grep: ShedLock 0건, `@Scheduled` 5곳 무보호 | ✅ (수치 정정: 4→5) |

---

## 5. 심각도·트리거 보정 (우선순위 판단 시 반영)

- **#6 포인트 역적립 왜곡** — 구매 후 `point.earn-rate` **설정이 바뀐 경우에만** 발생. rate 불변이면 정확. 단 가드 없음 + 고객 자산 오회수 + 수정 한 줄 → **우선 처리 유지**.
- **#5 멱등키 교차 유출** — 공격자가 피해자의 키 문자열을 **알아야** 성립(키는 보통 랜덤 UUID). #1·#2 IDOR처럼 즉시 순회 가능한 수준은 아님 → 설계 결함 + 정상 충돌 시 409 오작동 성격. 체감 위험도는 IDOR 두 건보다 한 단계 아래.
- **#9 JWT 가드** — 프로파일 누락 배포라는 **오설정 조건**에서만. 단 발생 시 전체 관리자 위조 → 블라스트 반경이 커 우선순위 유지.

---

## 6. 의사결정 로그 (Open Decisions)

작업 전 정해야 하는 정책. 결정 시 여기에 결론과 날짜를 기록한다.

- [x] **D-1. 전체취소 시 쿠폰·예산 복원 여부** — **결정(2026-07-06): 복원**. 전체취소(발송 전)는 고객이 쿠폰을 돌려받고 예산 카운터도 원장 환입과 일치. `refundLines`는 현행대로 미복원 유지. → 묶음 D에서 구현.
- [x] **A-1. 공개 프로필 노출 범위** — **결정(2026-07-06): 본인/ADMIN만 전체 조회**. `/members/{id}`는 본인 또는 ADMIN만 허용(별도 공개 프로필 DTO는 두지 않음). → 묶음 A에서 구현.
- [ ] **H-1. clawback 방식** — 차기 정산 음수 조정 라인 vs 미수금(AR) 계정 분리 추적.
- [ ] **K-1. 수평 확장 전제** — ShedLock 도입 vs "단일 인스턴스 전제" 문서화(포트폴리오 범위 판단).

---

## 7. 진행 로그 (Changelog)

> 형식: `YYYY-MM-DD | 묶음 | 내용` (최신이 위)

- 2026-07-06 | H | 정산 clawback 누적 모델(진행중). 정산액을 기간매출 → (총 미환불 매출 − 확정정산 합)으로 전환, 확정 후 환불 자동 clawback + 음수 이월. 스키마 변경 없음. subjectId·검증 강화는 H-2 후속. 테스트 SettlementClawbackTest.
- 2026-07-06 | B | 완료. PR #35 머지(merge `d5fab88`). 웨이브 2(E·G·B) 완료.
- 2026-07-06 | B | 인증 토큰 하드닝(핵심만, 진행중). JWT dev 시크릿 가드 반전(local/test/dev만 폴백), 정지/탈퇴 회원 Redis 블랙리스트(MemberTokenBlacklist, AFTER_COMMIT 이벤트 리스닝) + 필터 확인. 리프레시 토큰은 후속. 테스트 JwtTokenProviderSecretGuardTest·SuspendedMemberAuthTest.
- 2026-07-06 | G | 완료. PR #34 머지(merge `ddb160a`).
- 2026-07-06 | G | 프로모션 예산 신뢰성(진행중). DB(orders) 기준 Redis 예산 재동기화 스케줄러 + release Lua 하한 0 클램프, 허위 CouponRedemption 주석 정정. 테스트 PromotionBudgetResyncTest.
- 2026-07-06 | E | 완료. PR #33 머지(merge `882568e`).
- 2026-07-06 | E | 상품 상세 캐시 회복력(진행중). Redis 캐시-어사이드를 ProductDetailCache 컴포넌트로 분리, 조회/저장/무효화 try/catch 폴백 + TTL 25~35s 지터. 테스트 ProductDetailCacheTest. 웨이브 2 착수.
- 2026-07-06 | F | 완료. PR #32 머지(merge `eb650d4`). 웨이브 1(A·C·D·F) 완료.
- 2026-07-06 | F | 설정·배포 안전값 반영(진행중). HikariCP 풀 40 + 타임아웃/수명, server.shutdown graceful + lifecycle 30s, Redisson 타임아웃/재시도. 테스트 프로파일 풀 10 오버라이드.
- 2026-07-06 | D | 완료. PR #31 머지(merge `ed016db`).
- 2026-07-06 | D | 포인트·쿠폰 정합성 구현·검증(진행중). 부분환불 역적립을 기록 EARN 기준(`originalEarned`)으로 교체, 전체취소 시 쿠폰 `restore()` + 예산 반환. 테스트 OrderPartialRefundTest·OrderCouponDiscountTest 확장.
- 2026-07-06 | C | 완료. PR #30 머지(merge `0be4a66`).
- 2026-07-06 | C | 멱등성 견고화 구현·검증(진행중). 멱등키를 (memberId+method+URI+key) SHA-256 스코프로 변경(스키마 변경 없음), 고아 IN_PROGRESS 청소 스케줄러 추가. 신규 테스트 IdempotencyScopingTest·IdempotencyCleanupTest.
- 2026-07-06 | A | 완료. PR #29 머지(merge `40350aa`).
- 2026-07-06 | A | 보안 접근제어 구현·검증 완료(커밋 대기). SecurityConfig 기본 폐쇄 전환, transactions/members 조회 소유권 검증, AdminAuthorizationTest 매트릭스 +12건. 전체 테스트 스위트 그린. actuator는 K로 이관. 의사결정 D-1(취소 시 복원)·A-1(본인/ADMIN) 확정.
- 2026-07-06 | — | 트래커 문서 생성. 6개 영역 리뷰 + 상위 14개 발견 코드 재검증 완료. 묶음 A~M / 웨이브 1~4 계획 수립.
