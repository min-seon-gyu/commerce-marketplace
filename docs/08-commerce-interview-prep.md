# 커머스 JD 대비 면접 준비 가이드

> 이 문서는 커머스 백엔드(결제·정산·쿠폰·포인트·AI 프로모션) JD를 기반으로, 이 프로젝트에서 구현된 기능과 설계 의사결정을 면접 Q&A 형태로 정리합니다.
> 모든 기능은 현재 브랜치에 구현 완료된 상태입니다.

---

## 1. 커머스 JD 커버리지 매핑

| 커머스 JD 요구 항목 | 현재 구현 상태 | 어필 포인트 |
|---|---|---|
| 결제·정산·쿠폰·포인트 도메인 | ✅ 전체 구현 완료 | 복식부기 원장 위에서 쿠폰 할인(2-leg 2쌍)·포인트 적립(POINT_BALANCE 차변정상)·정산(gross) 모두 분개 |
| 대용량·실시간 처리 | ✅ 분산락·멱등·k6 부하테스트 구현 | Redisson 분산락 + DB 비관적 락 이중 방어, Redis Lua 원자 예산 카운터, k6 동시성 시나리오 |
| RDBMS / NoSQL 설계 | ✅ MySQL + Redis 구현 | Flyway 마이그레이션 정식화, Redis Lua 스크립트, QueryDSL 페이지네이션 |
| 재무 정합성·감사 추적 | ✅ 복식부기 원장 + 정합성 검증 배치 | `LedgerVerificationService`가 매일 `차변합==대변합` + `POINT_BALANCE` 전역 합산 검증 |
| 컨테이너·CI/CD | ✅ Dockerfile + docker-compose + GitHub Actions | 멀티스테이지 Dockerfile, 풀스택 compose(app+mysql+redis), CI(build+testcontainers) |
| AI 자동화 | ✅ AI 프로모션 어시스턴트 구현 | 자연어 → 프로모션 초안 생성, 결정적 가드레일, 모킹 계약 테스트 + 키 없는 부팅 |

---

## 2. 핵심 예상 면접 질문 & 답변

### Q1. 복식부기 원장이 커머스 결제·정산에서 왜 강점인가?

**배경:** 단순 잔액 차감은 "돈이 어디서 왔는지" 증명이 불가능하다.

**답변:**
모든 금전 변동을 `DEBIT 1행 + CREDIT 1행` 쌍으로 기록한다(`LedgerService.record()`). 불변 엔티티(`@Immutable`)로 UPDATE/DELETE를 원천 차단한다. 이를 통해:
- **결제·환불 분쟁**: 원장만으로 자금 흐름 완벽 추적
- **정산 정합성**: `COMPLETED` 거래의 `MERCHANT_RECEIVABLE` 합산 = 가맹점 수취액
- **쿠폰·포인트 연동**: 동일 원장 모델 위에 `PROMOTION_FUNDING`, `POINT_BALANCE` 계정 추가 → 기존 정합성 검증 그대로 재사용

매일 02:00 `LedgerVerificationService`가 전역 `차변합 == 대변합`을 검증하고 불일치 시 자동 수정 없이 CRITICAL 감사 로그를 남긴다(사람이 판단).

**꼬리질문 대비:** "캐시 잔액과 원장이 분리된 이유?" → 성능(캐시로 빠른 잔액 조회) + 정합성(원장이 진실의 원천). 불일치는 배치가 탐지한다.

---

### Q2. 쿠폰 할인을 2-leg 2쌍으로 분개한 이유는?

**배경:** `LedgerService.record()`는 차변 1행 + 대변 1행의 단일 2-leg 헬퍼다. 쿠폰 결제는 바우처 차감분과 플랫폼 보조분이라는 두 자금 흐름이 필요하다.

**분개 설계 (주문총액 T, 쿠폰 할인 D):**
```
쌍1 (바우처 결제분): DEBIT MERCHANT_RECEIVABLE (T−D) / CREDIT VOUCHER_BALANCE   (T−D)
쌍2 (플랫폼 보조분): DEBIT MERCHANT_RECEIVABLE (D)   / CREDIT PROMOTION_FUNDING (D)
─────────────────────────────────────────────────────────────────────────────
합계: 차변합(T) == 대변합(T) ✔  VOUCHER_BALANCE 감소액(T−D) ✔
```

**설계 이유:**
- 헬퍼 시그니처 불변 유지 (`record()` 수정 없음)
- `transactionId` 공유로 두 쌍을 하나의 거래로 묶음
- 가맹점은 gross `T`로 정산, 고객 바우처는 `T−D`만 차감
- 기존 정합성 검증(전역 차대변 균형)을 그대로 통과

---

### Q3. 예산 상한 Redis Lua 원자 제어와 DB 재동기화를 어떻게 구현했나?

**문제:** 쿠폰 예산 차감을 Redis와 DB로 동시에 제어할 때 비원자성으로 인한 예산 누수.

**해결:**
1. **Lua 스크립트로 원자 INCRBY + 한도 체크**: `INCRBY`와 한도 비교를 단일 Lua 스크립트로 묶어 레드 컨디션 제거
2. **DB 실패 시 보상 DECRBY**: `try/finally`로 다운스트림 DB 실패 시 즉시 Redis 롤백 (Lua 한도초과 롤백만으로 부족 — 커밋된 INCRBY + 롤백된 결제 = 예산 누수)
3. **예산 재동기화 잡**: `RegionCounterSyncScheduler` 패턴으로 Redis ↔ DB 정기 동기화
4. **취소·환불 시 예산 반환**: 쿠폰 상태를 `REDEEMED→CANCELLED`로 전이하며 `DECRBY`로 예산 반환

**핵심 코드 위치:** `PromotionBudgetService`, `RegionCounterSyncScheduler`

---

### Q4. 결합결제(바우처 + 쿠폰) 락 순서와 데드락 회피를 어떻게 설계했나?

**문제:** 두 개 이상 자원에 락을 걸면 순서가 다를 때 데드락 발생.

**해결 — 정준 락 순서:**
항상 `coupon:{id} → voucher:{id}` 순서로 획득 (키 정렬 규칙 적용).

```kotlin
// 결합결제 오케스트레이터
lockManager.withCouponLock(couponId) {          // 1st: 쿠폰 락
    lockManager.withVoucherLock(voucherId) {     // 2nd: 바우처 락
        transactionTemplate.execute { _ ->       // 3rd: DB 트랜잭션 시작
            // 쿠폰 검증/예약 → Lua 예산 예약 → 바우처 차감 → 원장 2쌍 분개
        }
    }
}
```

**흐름:** 락 획득 → tx 시작 → 쿠폰 검증/예약 → 예산 Lua 예약 → 바우처 차감 → 원장 2쌍 분개(동기) → tx 커밋 → 락 해제. 실패 시 보상 `DECRBY` + tx 롤백.

**동시성 테스트:** 동일 쿠폰/예산 N스레드 → 예산 초과·중복 사용 0건, 원장 `isBalanced` 검증.

---

### Q5. 포인트를 `POINT_BALANCE` 차변정상으로 모델링한 이유는?

**배경:** 포인트 잔액을 대변정상(`POINT_LIABILITY`)으로 모델링하면 기존 검증 공식(`net = 차변 − 대변`)과 sign이 꼬인다.

**결정:** `POINT_BALANCE`를 **차변정상**으로 설계 → `VOUCHER_BALANCE`와 동일 취급.

**적립 분개 (적립액 E):**
```
DEBIT POINT_BALANCE (E) / CREDIT POINT_FUNDING (E)
```
→ 포인트 증가 = 차변 증가, 기존 `netBalanceByAccount()` 검증 공식 재사용, sign 꼬임 제거.

**정합성 검증:** 전역 `차변합 == 대변합`은 자동 커버. 추가로 `netBalanceByAccount(POINT_BALANCE) == Σ PointAccount.balance` 전역 검증을 일일 배치에 포함.

**적립 기준:** 쿠폰 할인 적용 후 실제 결제액 기준, 포인트로 결제한 금액엔 미적립(point-on-point 제외), 1원 단위 반올림.

---

### Q6. AI 어시스턴트의 결정적 가드레일과 프롬프트 인젝션 차단을 어떻게 구현했나?

**설계 원칙:** AI는 제안만 하고, 서버가 결정적으로 검증·확정한다. AI가 DB에 직접 쓰지 않는다.

**흐름:**
```
POST /api/promotions/draft (자연어) 
  → PromotionDraftService (Claude API 호출, structured output)
  → 서버사이드 가드레일 검증 (결정적):
      - 예산 한도 범위 검증
      - 날짜 유효성 검증
      - 최소 할인 / 최대 할인 정책 검증
  → 위반 시 거부 + 사유 (silent 통과 0건)
  → 통과 시 초안 반환 → 사람이 검토 후 POST /api/promotions 확정
```

**프롬프트 인젝션 차단:** 자연어 입력이 어떤 지시를 담더라도 서버사이드 결정적 검증이 최종 관문. AI 출력의 스키마를 `structured output`으로 강제하여 임의 텍스트 주입 방지.

**운영 가드:**
- 요청당 최대 토큰/비용 상한 설정
- connect/read 타임아웃 + 재시도/백오프 + 서킷브레이커
- API 키 없이도 앱·CI 부팅되는 kill-switch 플래그 (`ai.promotion.enabled: false`)
- 관측성: `ai.promotion.draft.latency`, `tokens`, `failure` 메트릭

---

### Q7. 멱등성(exactly-once)을 어떻게 보장하는가?

**구현:** Redis(TTL 24시간, 1차) + DB(2차, Redis 장애 대비) 이중 저장.

```
요청 수신 → Redis에서 Idempotency-Key 확인
  → 이미 있으면: 저장된 응답(본문 + 상태코드) 그대로 반환 (409 아님)
  → 없으면: 비즈니스 처리 → 결과를 Redis + DB에 저장
```

**핵심 설계:**
- `@Idempotent` 어노테이션 + `ResponseBodyAdvice`로 컨트롤러 코드 수정 없이 자동 적용
- 중복 감지 시 409가 아닌 **원래 응답을 원래 상태코드와 함께 반환** → 클라이언트가 정상 흐름 이어감
- 결제·구매·환불·취소 등 모든 금전 API에 적용

**AI 드래프트에도 적용:** 중복 과금 방지를 위해 draft 엔드포인트도 멱등성 보장.

---

### Q8. 대용량 동시성을 어떻게 증명했나? (k6 + 동시성 테스트)

**동시성 테스트 (Testcontainers + CountDownLatch):**
```
50,000원 바우처 × 10,000원 결제 × 10스레드 동시 →
  성공 5건 + 실패 5건 (모두 INSUFFICIENT_BALANCE)
  잔액 = 0원 (음수 불가 불변식 보장)
  원장 차대변 균형 (재무 불변식 보장)
```

**쿠폰/예산 동시성 테스트:**
```
동일 쿠폰/예산 N스레드 동시 사용 →
  예산 초과 0건, 중복 사용 0건, 원장 isBalanced ✔
```

**k6 부하테스트 (load-test/ 디렉터리):**
- 쿠폰 예산 핫스팟 시나리오: 높은 동시성에서 예산 상한 정확 제어 확인
- Prometheus/Grafana 연동으로 `voucher.redemption.duration`, `lock.acquisition.timeout` 등 실시간 관측

**이중 방어 전략:**
| 작업 | 전략 |
|---|---|
| 바우처 결제 | Redisson 분산락 + DB `SELECT FOR UPDATE` |
| 예산 차감 | Redis Lua 원자 스크립트 |
| 구매 한도 | Member 분산락 + Lua Region 한도 체크 |
| 가맹점 수정 | JPA `@Version` 낙관적 락 |

---

### Q9. 보상 트랜잭션(Compensating Transaction)이 결합결제 취소에서 어떻게 동작하나?

**현재 취소 구현 (`TransactionCancelService`):**
```kotlin
// 모든 leg 역분개 (결합결제 대응)
// 바우처 결제분 역분개:
ledgerService.record(VOUCHER_BALANCE, MERCHANT_RECEIVABLE, T−D, cancelTxId, CANCELLATION)
// 플랫폼 보조분 역분개:
ledgerService.record(PROMOTION_FUNDING, MERCHANT_RECEIVABLE, D, cancelTxId, CANCELLATION)
// 상태/자원 복원:
voucher.restoreBalance(T−D)       // 바우처 잔액 복원
coupon.cancel()                    // 쿠폰 REDEEMED → CANCELLED
promotionBudgetService.return(D)   // 예산 반환 (DECRBY)
```

**불변식 보장:**
- 원 거래 기록 불변 (DELETE/UPDATE 없음)
- 취소 후 `isBalanced` 검증 통과
- 쿠폰 상태·예산·잔액 모두 원복

---

### Q10. JWT 인증 필터를 신규 엔드포인트부터 적용한 이유는?

**기존 문제:** `SecurityConfig.permitAll()` + body로 `memberId` 전달 → 신원 위조 가능.

**설계 결정 (MUST vs STRETCH 분리):**
- **MUST (구현 완료):** `OncePerRequestFilter` JWT 검증 필터를 보안 체인에 연결. **신규 쿠폰·포인트·프로모션 엔드포인트는 처음부터 인증 principal에서 신원 도출** (body ID 미신뢰).
- **STRETCH:** 기존 voucher/settlement 엔드포인트 전수 retrofit (컨트롤러 시그니처·DTO·테스트 전부 변경 → cross-cutting, 비용 큼).

**이유:** 신규 표면을 처음부터 안전하게 만드는 것이 신뢰성 이득의 대부분을 저비용에 확보. 비싼 부분은 분리.

---

### Q11. Flyway를 조기 도입한 이유는?

**문제:** `ddl-auto: update`는 운영 환경에서 스키마 변경을 추적·검증할 수 없다.

**해결:**
1. 현재 안정 스키마를 `V1__baseline.sql`로 베이스라인 (Hibernate DDL export로 정확히 매칭)
2. `ddl-auto: validate` + `flyway.enabled: true`로 조기 전환 → 스키마 불일치를 부팅·테스트에서 즉시 노출
3. 신규 도메인 테이블은 생성할 때마다 per-domain 마이그레이션 파일 추가

**이점:** V1이 생성 DDL과 일치하지 않으면 전체 Testcontainers 테스트가 동시에 깨짐 → 조기 발견.

---

## 3. AI 세션 아티팩트 요지 (면접 소재)

### 3.1 모델 선택 근거

| 사용 케이스 | 선택 모델 | 이유 |
|---|---|---|
| 프로모션 초안 생성 | Claude (저비용 Haiku/Sonnet) | structured output 지원, 토큰당 비용 대비 품질 적합 |
| 복잡한 조건 추론 | Claude Sonnet | 다중 조건(기간·할인율·예산·대상) 추론 정확도 우위 |

### 3.2 출력 스키마 (structured output)

```json
{
  "name": "string",
  "discountType": "FIXED | PERCENTAGE",
  "discountValue": "number",
  "budgetLimit": "number",
  "startDate": "ISO-8601",
  "endDate": "ISO-8601",
  "minSpend": "number",
  "targetCondition": "string"
}
```

- JSON 스키마 강제로 임의 텍스트 주입 방지
- 스키마 불일치 시 결정적 에러 (부분/오염 데이터 0건)

### 3.3 가드레일 (결정적 서버사이드 검증)

| 검증 항목 | 규칙 |
|---|---|
| 예산 한도 | `budgetLimit` ≤ 설정된 최대 예산 |
| 날짜 유효성 | `startDate < endDate`, `endDate > now` |
| 할인 범위 | 0 < `discountValue` ≤ 정책 최대 할인율/금액 |
| 최소 주문 | `minSpend` ≥ 0 |

모든 위반은 **거부 + 사유 반환** (silent 통과 0건).

### 3.4 테스트 전략

- **골든셋 회귀**: 대표 프로모션 시나리오 N건의 AI 출력을 기대 초안과 비교. 모델 업그레이드 시 회귀 방지.
- **모킹 계약 테스트**: CI에서 Claude API를 목(Mock)으로 대체 → 실 API 키 불필요, 비용 0. 가드레일 거부 케이스·LLM throw·스키마 불일치 케이스 모두 테스트.
- **키 없는 부팅**: `ai.promotion.enabled: false` kill-switch로 API 키 없어도 앱·CI 정상 부팅.

### 3.5 면접 스토리 핵심

> "AI를 협업자로, 가드레일과 함께 안전 운영한다."
> AI 출력을 그대로 DB에 쓰지 않는다. 서버의 결정적 검증이 최종 관문이며, AI는 초안 생성 가속 도구다. 프롬프트 인젝션이나 환각이 발생해도 가드레일이 차단하므로 운영 안전성이 보장된다.

---

## 4. 면접 STAR 프레임워크 예시

### 예시: "동시성 문제를 해결한 경험을 말해주세요"

- **Situation**: 쿠폰 예산 소진 시나리오에서 N스레드 동시 요청이 예산을 초과할 수 있는 상황
- **Task**: 예산 정확도와 처리 성능을 동시에 보장해야 함
- **Action**: Redis Lua 스크립트로 INCRBY + 한도 체크를 원자화. DB 실패 시 보상 DECRBY. 재동기화 잡으로 Redis ↔ DB 정합 복구. k6로 핫스팟 시나리오 부하테스트.
- **Result**: N스레드 동시 요청에서 예산 초과 0건, 원장 균형 유지, p99 지연시간 목표 이하 달성.

---

## 5. 자주 나오는 꼬리질문 대비

| 꼬리질문 | 핵심 답변 |
|---|---|
| "Redis 장애 시 예산 제어는?" | DB fallback + 재동기화 잡. Lua 스크립트 실패 시 보수적으로 거부. |
| "쿠폰 적립과 포인트 결제가 동시면?" | 정준 락 순서(coupon→voucher→point) + 단일 DB tx. |
| "AI 토큰 비용이 급증하면?" | 요청당 최대 토큰 상한 + 서킷브레이커 + 비용 메트릭 알림. |
| "정합성 검증 배치가 느리면?" | REPEATABLE_READ 스냅샷, 청크 단위 처리. 불일치만 보고, 자동 수정 없음. |
| "Flyway migrate 실패하면?" | 앱 부팅 자체가 실패 → 즉시 인지. 롤백은 역방향 마이그레이션 파일 추가. |
| "AI 모델을 바꾸면?" | 골든셋 회귀 테스트로 즉시 품질 검증. 모델 ID를 설정값으로 외부화. |
