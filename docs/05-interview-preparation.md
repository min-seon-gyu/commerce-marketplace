# 면접 대비 완전 가이드 — 커머스 결제·프로모션 백엔드

> 이 문서는 해당 프로젝트를 기반으로 기술 면접에서 나올 수 있는 **모든 질문**을 정리합니다.
> 각 질문에 대해 **초보자도 이해할 수 있는 배경 설명** + **모범 답변** + **꼬리질문**을 포함합니다.
> 면접관이 "왜?"를 3번 이상 물어도 대답할 수 있도록 깊이 있게 준비했습니다.

---

## 목차

1. [동시성 제어 (Concurrency Control)](#1-동시성-제어-concurrency-control)
2. [복식부기 원장 (Double-Entry Ledger)](#2-복식부기-원장-double-entry-ledger)
3. [보상 트랜잭션 (Compensating Transaction)](#3-보상-트랜잭션-compensating-transaction)
4. [도메인 설계 (DDD / State Machine)](#4-도메인-설계-ddd--state-machine)
5. [이벤트 & 감사 로그](#5-이벤트--감사-로그-event--audit)
6. [테스트 전략](#6-테스트-전략-testing-strategy)
7. [멱등성 (Idempotency)](#7-멱등성-idempotency)
8. [아키텍처 & 설계 원칙](#8-아키텍처--설계-원칙)
9. [Spring Boot / JPA 심화](#9-spring-boot--jpa-심화)
10. [보안 (Security)](#10-보안-security)
11. [성능 & 모니터링](#11-성능--모니터링)
12. [데이터베이스 설계](#12-데이터베이스-설계)
13. [Kotlin 특화 질문](#13-kotlin-특화-질문)
14. [금융 도메인 질문](#14-금융-도메인-질문)
15. [시스템 장애 대응](#15-시스템-장애-대응)
16. [MSA 전환 & 확장성](#16-msa-전환--확장성)
17. [코드 품질 & 설계 의사결정](#17-코드-품질--설계-의사결정)
18. [실무/상황 질문](#18-실무상황-질문)
19. [면접 팁 & STAR 프레임워크](#19-면접-팁--star-프레임워크)

---

## 1. 동시성 제어 (Concurrency Control)

### 배경 지식 (초보자용)

**동시성 문제란?**

여러 사용자가 "동시에" 같은 데이터를 수정하려 할 때 발생하는 문제입니다.

```
예시: 잔액 50,000원인 상품권에 A, B가 동시에 30,000원 결제 시도

[시나리오: 동시성 제어 없음]
A: 잔액 읽기 → 50,000원
B: 잔액 읽기 → 50,000원 (A의 변경 전에 읽음!)
A: 50,000 - 30,000 = 20,000원 → DB에 저장
B: 50,000 - 30,000 = 20,000원 → DB에 저장

결과: 잔액 20,000원 (60,000원이 빠져나갔는데 30,000원만 차감됨!)
→ 10,000원이 공중에서 생겨난 것과 같음 (Lost Update 문제)
```

이 문제를 해결하는 것이 **동시성 제어**입니다.

**잠금(Lock)이란?**

"내가 이 데이터를 사용하고 있으니, 다른 사람은 기다려!"라고 선언하는 것입니다.
- **낙관적 잠금(Optimistic Lock)**: "충돌이 거의 없을 것"이라고 가정. 수정 시점에 충돌 감지
- **비관적 잠금(Pessimistic Lock)**: "충돌이 발생할 것"이라고 가정. 읽는 순간부터 잠금

---

### Q1-1. 상품권 결제(redeem) 시 동시성을 어떻게 제어했나요?

**모범 답변:**

이중 잠금(Dual Lock) 전략을 사용했습니다. 두 겹의 보호막을 친 것입니다.

**1층 방어: Redisson 분산락 (Redis 기반)**
```kotlin
// VoucherLockManager.kt
fun <T> withVoucherLock(voucherId: Long, action: () -> T): T =
    withLock("voucher:$voucherId", action)

private fun <T> withLock(key: String, action: () -> T): T {
    val lock = redissonClient.getLock(key)
    // 최대 5초 동안 락 획득 시도, 획득 후 10초간 보유
    val acquired = lock.tryLock(5, 10, TimeUnit.SECONDS)
    if (!acquired) throw BusinessException(ErrorCode.LOCK_ACQUISITION_FAILED)
    try {
        return action()
    } finally {
        if (lock.isHeldByCurrentThread) lock.unlock()
    }
}
```

**2층 방어: DB Pessimistic Lock (MySQL `SELECT FOR UPDATE`)**
```kotlin
// VoucherRedemptionService.kt 내부
val voucher = voucherRepository.findByIdForUpdate(voucherId)
// SELECT * FROM vouchers WHERE id = ? FOR UPDATE
// → 이 행에 대한 다른 모든 읽기/쓰기가 차단됨
```

**왜 두 개를 같이 사용하나요?**

| 상황 | 1층(Redis)만 | 2층(DB)만 | 둘 다 |
|------|------------|----------|-------|
| Redis 정상 | 빠른 잠금 O | 불필요한 DB 부하 | O |
| Redis 장애 | 잠금 불가! | DB가 방어 | O |
| 다중 서버 | 서버 간 동기화 O | 같은 DB면 OK | O |

**핵심: Lock-Commit 순서**

```
┌─── 분산락 보유 구간 ─────────────────────────────┐
│                                                    │
│   TransactionTemplate.execute {                    │
│       ┌─── DB 트랜잭션 ─────────────────────┐     │
│       │                                      │     │
│       │   SELECT FOR UPDATE (2차 잠금)       │     │
│       │   voucher.redeem(amount)              │     │
│       │   INSERT Transaction                  │     │
│       │   INSERT LedgerEntry × 2              │     │
│       │                                      │     │
│       │   === COMMIT === (데이터 확정!)       │     │
│       └──────────────────────────────────────┘     │
│                                                    │
│   === UNLOCK === (다음 스레드가 읽을 때 커밋된 데이터 보장)
└────────────────────────────────────────────────────┘
```

**만약 순서가 반대라면? (UNLOCK → COMMIT)**
```
Thread A: UNLOCK (아직 COMMIT 안 됨!)
Thread B: LOCK 획득 → 읽기 → A가 아직 커밋 안 한 데이터를 읽음!
Thread A: COMMIT
→ Thread B는 잘못된 데이터로 작업 → 버그 발생!
```

---

#### 꼬리질문 1-1-1: 왜 `@Transactional` 대신 `TransactionTemplate`을 사용했나요?

**배경 설명:**

Spring에서 트랜잭션을 관리하는 2가지 방법:

| 방법 | 설명 | 트랜잭션 시작 시점 |
|------|------|-------------------|
| `@Transactional` (선언적) | 메서드에 어노테이션 붙이기 | **메서드 진입 시** 자동 시작 |
| `TransactionTemplate` (프로그래밍) | 코드로 직접 범위 지정 | **execute() 호출 시** 시작 |

**문제 상황:**

```kotlin
// 잘못된 방식: @Transactional + 분산락
@Transactional  // ← 메서드 시작 시 트랜잭션 열림
fun redeem(voucherId: Long, amount: BigDecimal) {
    lockManager.withVoucherLock(voucherId) {
        // 비즈니스 로직...
    }
    // 여기서 메서드 끝 → 트랜잭션 커밋
}

// 실행 순서:
// 1. 트랜잭션 시작 (메서드 진입)
// 2. 분산락 획득
// 3. 비즈니스 로직
// 4. 분산락 해제  ← ❌ 여기서 다른 스레드가 들어올 수 있음!
// 5. 트랜잭션 커밋 ← ❌ 아직 커밋 안 됨!
```

```kotlin
// 올바른 방식: TransactionTemplate
fun redeem(voucherId: Long, amount: BigDecimal) {
    lockManager.withVoucherLock(voucherId) {
        // 분산락 내부에서 트랜잭션 시작
        transactionTemplate.execute {
            // 비즈니스 로직...
        }
        // execute() 끝 = 커밋 완료!
    }
    // 커밋 후 분산락 해제
}

// 실행 순서:
// 1. 분산락 획득
// 2. 트랜잭션 시작
// 3. 비즈니스 로직
// 4. 트랜잭션 커밋 ← ✅ 먼저!
// 5. 분산락 해제   ← ✅ 나중!
```

**요약:** `TransactionTemplate`을 쓰면 "커밋 → 락 해제" 순서를 코드로 명시적으로 보장할 수 있습니다.

---

#### 꼬리질문 1-1-2: Redis가 다운되면 어떻게 되나요?

**모범 답변:**

단기적으로:
1. 분산락 획득 시도 → 5초 타임아웃 → `LOCK_ACQUISITION_FAILED` 예외
2. 클라이언트에게 503 (Service Unavailable) 응답
3. **하지만 DB의 `SELECT FOR UPDATE`가 2차 방어선으로 작동**

DB만으로 동시성을 제어할 수 있는 이유:
- `SELECT FOR UPDATE`는 해당 행에 배타적 잠금을 걸어 다른 트랜잭션이 접근 못 함
- 단, DB 수준 잠금은 커넥션을 점유하므로 대량 트래픽 시 커넥션 풀 고갈 위험

운영 대응:
- Redis Sentinel (자동 장애 복구, 3대 이상 구성)
- Redis Cluster (분산 구성)
- Health Check + Alert (Redis 응답 시간 > 100ms면 경고)

---

#### 꼬리질문 1-1-3: tryLock의 waitTime(5초)과 leaseTime(10초)은 어떤 기준으로 정했나요?

**모범 답변:**

```kotlin
lock.tryLock(
    5,    // waitTime: 락 획득을 위해 최대 5초 대기
    10,   // leaseTime: 락을 최대 10초간 보유
    TimeUnit.SECONDS
)
```

**waitTime = 5초 산정 근거:**
- 결제 비즈니스 로직 평균 처리 시간: ~200ms
- 최악의 경우(DB slow query + GC pause): ~2초
- 앞에 1명이 처리 중이라면 2초 후 내 차례
- 앞에 2명이 대기 중인 극단적 상황까지 고려: 2초 × 2 = 4초
- 여유분 1초 추가 → **5초**
- 5초 넘게 대기하면 사용자 경험이 나빠지므로 타임아웃이 합리적

**leaseTime = 10초 산정 근거:**
- 비즈니스 로직(200ms) + DB 커밋(100ms) + 네트워크 지연(100ms) = 약 400ms
- JVM Full GC pause: 최대 2-3초
- 네트워크 재시도 등 예외 상황: ~5초
- **서버가 죽었을 때 자동 해제** 보장: 10초면 충분히 안전
- 너무 길면(예: 60초): 서버 장애 시 1분간 해당 상품권 결제 불가 → 사용자 불만

**핵심 원리:** 
- waitTime > 평균 처리시간 × 대기 가능 인원수
- leaseTime > 최악의 처리시간 (하지만 합리적 범위 내)

---

#### 꼬리질문 1-1-4: `isHeldByCurrentThread` 체크는 왜 하나요?

**배경:**

```kotlin
private fun <T> withLock(key: String, action: () -> T): T {
    val lock = redissonClient.getLock(key)
    val acquired = lock.tryLock(5, 10, TimeUnit.SECONDS)
    
    if (!acquired) {
        // 락 획득 실패! → 예외 던짐
        throw BusinessException(ErrorCode.LOCK_ACQUISITION_FAILED)
    }
    
    try {
        return action()
    } finally {
        // ⭐ 이 체크가 없으면?
        if (lock.isHeldByCurrentThread) lock.unlock()
    }
}
```

**왜 필요한가:**

시나리오 1: 정상 흐름
```
tryLock() → acquired = true → action() → finally: unlock() ✅
```

시나리오 2: leaseTime 초과 (비즈니스 로직이 10초 이상 걸린 경우)
```
tryLock() → acquired = true → action()이 12초 걸림 → 10초에 자동 해제!
→ finally 도달 시 이미 락이 풀려 있음
→ unlock() 호출하면 IllegalMonitorStateException 발생!
→ isHeldByCurrentThread로 체크하면 안전하게 스킵
```

시나리오 3: 비즈니스 로직에서 예외 발생 시
```
tryLock() → acquired = true → action()에서 예외 → finally: unlock() ← 정상 해제 필요
```

**결론:** 방어적 프로그래밍의 일환. 예상치 못한 상황에서 추가 예외가 발생하는 것을 방지.

---

#### 꼬리질문 1-1-5: Optimistic Lock과 Pessimistic Lock의 차이를 설명해주세요

**초보자용 비유:**

- **낙관적 잠금 (Optimistic Lock)**: 
  "충돌 안 날 거야~" → 편하게 작업 → 저장할 때 "혹시 누가 먼저 바꿨나?" 확인
  → 바뀌었으면 에러 → 처음부터 다시

- **비관적 잠금 (Pessimistic Lock)**:
  "다른 사람이 건드릴 수 있어!" → 먼저 "사용 중" 푯말 꽂기 → 안전하게 작업 → 완료 후 푯말 제거

**코드 비교:**

```kotlin
// 낙관적 잠금 (BaseEntity의 @Version)
@Version
val version: Long = 0L

// JPA가 자동으로 이렇게 변환:
// UPDATE vouchers SET balance = ?, version = 2 WHERE id = ? AND version = 1
// → version이 1이 아니면 (다른 사람이 먼저 수정했으면) 0 rows updated → 예외!
```

```kotlin
// 비관적 잠금 (Repository 메서드)
@Lock(LockModeType.PESSIMISTIC_WRITE)
@Query("SELECT v FROM Voucher v WHERE v.id = :id")
fun findByIdForUpdate(id: Long): Voucher?

// 실제 SQL: SELECT * FROM vouchers WHERE id = ? FOR UPDATE
// → 다른 트랜잭션이 이 행을 읽지도 못함 (대기!)
```

**언제 뭘 쓰나:**

| 상황 | 추천 방식 | 이유 |
|------|----------|------|
| 게시글 수정 | 낙관적 | 동시 수정 확률 낮음, 재시도 비용 낮음 |
| 상품 재고 차감 | 비관적 | 동시 주문 빈번, 과매출 방지 필수 |
| **상품권 결제** | **비관적** | 금전 관련, 정확성이 최우선 |
| 프로필 업데이트 | 낙관적 | 한 사람만 수정, 충돌 거의 없음 |

---

### Q1-2. 10개 스레드가 동시에 결제하면 어떻게 되나요?

**모범 답변 (ConcurrencyTest 기반):**

```kotlin
// 상황: 50,000원 상품권, 10개 스레드가 각 10,000원 결제 시도
val voucher = fixtures.issueVoucher(memberId, regionId, BigDecimal("50000"))
val threadCount = 10

// CountDownLatch: 모든 스레드를 동시에 "출발!"시키는 신호총
val latch = CountDownLatch(1)

(1..threadCount).map {
    executor.submit {
        latch.await()   // 모든 스레드가 여기서 대기
        // 결제 시도...
    }
}
latch.countDown()  // 10개 스레드 동시 출발!
```

**실행 결과:**

```
Thread 1: 락 획득 → 잔액 50,000 → 40,000으로 변경 → 커밋 → 락 해제 ✅ (성공)
Thread 2: 락 획득 → 잔액 40,000 → 30,000으로 변경 → 커밋 → 락 해제 ✅ (성공)
Thread 3: 락 획득 → 잔액 30,000 → 20,000으로 변경 → 커밋 → 락 해제 ✅ (성공)
Thread 4: 락 획득 → 잔액 20,000 → 10,000으로 변경 → 커밋 → 락 해제 ✅ (성공)
Thread 5: 락 획득 → 잔액 10,000 →     0으로 변경 → 커밋 → 락 해제 ✅ (성공)
Thread 6: 락 획득 → 잔액     0 < 10,000 → INSUFFICIENT_BALANCE ❌ (실패)
Thread 7: 락 획득 → 잔액     0 < 10,000 → INSUFFICIENT_BALANCE ❌ (실패)
...

최종: 5건 성공, 5건 실패, 잔액 = 0원 (정확!)
```

**검증 포인트 4가지:**
1. `balance >= 0` → 음수가 되지 않음 (불변식 I1)
2. `successCount == 5` → 50,000 / 10,000 = 정확히 5건
3. 실패 원인 = `INSUFFICIENT_BALANCE` (정상적 비즈니스 실패, 락 타임아웃 아님)
4. 원장 `sum(DEBIT) == sum(CREDIT)` → 복식부기 균형 유지 (불변식 I2)

---

#### 꼬리질문 1-2-1: 실패 원인이 LOCK_ACQUISITION_FAILED가 아닌 이유는?

**모범 답변:**

분산락이 "직렬화(Serialize)"를 하기 때문입니다.

```
10개 스레드가 동시에 도착해도:
→ 분산락 덕분에 1개씩 순서대로 처리됨
→ 각 스레드의 처리 시간은 ~200ms
→ 10번째 스레드도 200ms × 9 = 1.8초 대기로 락 획득 가능 (5초 내)
→ 따라서 모든 스레드가 락을 획득하고 비즈니스 로직까지 도달
→ 6~10번째 스레드는 잔액 부족으로 INSUFFICIENT_BALANCE 실패
```

만약 `LOCK_ACQUISITION_FAILED`가 발생한다면:
- 처리 시간이 너무 길거나 (leaseTime 초과)
- Redis에 문제가 있거나
- 동시 요청이 너무 많은 것 → 인프라 스케일업 필요

---

### Q1-3. 월 발행한도를 Redis Lua 스크립트로 검증한 이유는?

**배경 설명:**

"서울시가 이번 달에 최대 10억원까지 상품권을 발행할 수 있다"는 규칙이 있을 때,
수천 명이 동시에 구매하면 한도를 정확히 지킬 수 있을까?

**문제: 일반적인 방법의 Race Condition**

```
한도: 1,000만원, 현재 발행량: 950만원

Thread A: Redis GET → 950만원 → 950만 + 30만 = 980만 < 1000만 → OK!
Thread B: Redis GET → 950만원 → 950만 + 80만 = 1030만 > 1000만 → 거절...

??? 하지만 둘 다 950만으로 읽으면:
Thread A: 950만 + 30만 = 980만 → OK → INCR → 980만
Thread B: 950만 + 80만 = 1030만... 

이것도 타이밍 문제:
Thread A: GET = 950만
Thread B: GET = 950만 (A가 INCR하기 전에 읽음!)
Thread A: INCR → 980만
Thread B: 950만 + 80만 = 1030만 → OK라고 판단! → INCR → 1060만!!! 💥
→ 한도 초과!
```

**해결: Redis Lua 스크립트 (원자적 실행)**

```lua
-- 이 스크립트 전체가 하나의 원자 연산으로 실행됨 (중간에 끊기지 않음!)
local current = redis.call('INCRBY', KEYS[1], ARGV[1])  -- 먼저 증가
if current > tonumber(ARGV[2]) then                      -- 한도 비교
    redis.call('DECRBY', KEYS[1], ARGV[1])               -- 초과면 되돌림
    return -1                                             -- 실패 신호
end
return current                                           -- 성공! 새 합계 반환
```

**왜 원자적인가?**

Redis는 **싱글 스레드**입니다. Lua 스크립트는 실행되는 동안 다른 어떤 명령도 끼어들 수 없습니다.

```
일반 명령 (3개의 독립적 연산):
  T1: INCRBY → [다른 클라이언트 명령이 끼어들 수 있음!] → GET → [또 끼어들 수 있음!] → DECRBY

Lua 스크립트 (1개의 원자 연산):
  T1: [INCRBY + 비교 + 필요시 DECRBY] ← 이 전체가 한 덩어리. 아무도 끼어들 수 없음!
```

---

#### 꼬리질문 1-3-1: Lua 스크립트 실행 중 다른 요청은 어떻게 되나요?

**모범 답변:**

Redis는 싱글 스레드이므로, Lua 스크립트 실행 중 다른 모든 명령은 **큐에서 대기**합니다.

```
시간축 →

Redis:  [Lua 실행 중...] [대기 명령 1] [대기 명령 2] [대기 명령 3]
         ↑ 여기서 다른 모든 것이 블로킹됨

Lua 스크립트가 1초 걸리면 → 모든 Redis 명령이 1초 지연!
```

**그래서 Lua 스크립트는 반드시 빨라야 합니다.**

이 프로젝트의 스크립트:
- `INCRBY` (O(1))
- 숫자 비교 (O(1))
- 조건부 `DECRBY` (O(1))
- **총 시간: 마이크로초 수준 (0.001ms 미만)**

만약 느린 작업(예: 전체 키 순회)을 Lua로 하면 Redis가 멈추므로 절대 하면 안 됩니다.

---

#### 꼬리질문 1-3-2: Redis가 재시작되면 카운터가 초기화되는 문제는?

**모범 답변:**

**문제:** Redis는 메모리 DB이므로 재시작하면 데이터가 사라짐

```
월 발행량: 950만원 (Redis에만 있음)
Redis 재시작!
월 발행량: 0원 ← 잘못됨! 이미 950만원 발행했는데!
→ 추가로 1000만원 발행 가능해짐 → 한도(1000만원) 초과 → 공공재원 낭비!
```

**해결 전략 (2중 보호):**

1. **RegionCounterSyncScheduler**: 매시간 DB에서 실제 발행량을 Redis에 동기화
   ```
   매시간: Redis 값 = DB의 SUM(faceValue) WHERE regionId AND 이번 달
   → Redis 재시작 후 최대 1시간 내 복구
   ```

2. **DB 검증 (최종 방어선)**: Redis 통과 후에도 DB에서 한 번 더 확인
   ```kotlin
   val totalPurchased = voucherRepository.sumFaceValueByMemberAndRegion(memberId, regionId)
   if (totalPurchased + faceValue > region.policy.purchaseLimitPerPerson)
       throw BusinessException(ErrorCode.MEMBER_PURCHASE_LIMIT_EXCEEDED)
   ```

3. **Redis TTL 설정**: 카운터 키에 월말까지의 만료 시간 설정
   ```kotlin
   if (counter.remainTimeToLive() == -1L) {
       val endOfMonth = YearMonth.now().atEndOfMonth().plusDays(1)
       counter.expire(Duration.between(LocalDateTime.now(), endOfMonth.atStartOfDay()))
   }
   ```

---

### Q1-4. `@Version` (Optimistic Lock)도 BaseEntity에 있는데, 왜 Pessimistic Lock을 추가로?

**모범 답변:**

```kotlin
// BaseEntity.kt
@Version
val version: Long = 0L
```

`@Version`은 모든 엔티티에 기본 적용됩니다. 하지만 **상품권 결제**에서는 부족합니다.

**@Version만 사용했을 때의 문제:**

```
Thread A: SELECT (version=1) → 잔액 50,000
Thread B: SELECT (version=1) → 잔액 50,000
Thread A: UPDATE SET balance=20000, version=2 WHERE id=1 AND version=1 → 성공!
Thread B: UPDATE SET balance=20000, version=2 WHERE id=1 AND version=1 → 0 rows! → 예외!
Thread B: 재시도 필요... 하지만 어떻게?
```

**문제점:**
1. **재시도 로직**을 직접 구현해야 함 (몇 번? 간격은?)
2. 동시 요청이 많으면 **재시도 폭주** (10개 중 1개만 성공, 9개 재시도)
3. 재시도 중 상태가 바뀌면 로직이 복잡해짐
4. **사용자 경험 저하**: 재시도 동안 응답 지연

**Pessimistic Lock의 장점:**
- 재시도 불필요! 순서대로 처리됨
- 코드가 단순 (실패/재시도 분기 없음)
- 금융 시스템에서는 "순서 보장"이 "빠른 응답"보다 중요

**정리:**
- Region, Member, Merchant → `@Version`만으로 충분 (동시 수정 드뭄)
- Voucher 결제 → Pessimistic Lock 필수 (동시 결제 빈번 + 금전)

---

## 2. 복식부기 원장 (Double-Entry Ledger)

### 배경 지식 (초보자용)

**복식부기란?**

가계부(단식부기)와 회사 장부(복식부기)의 차이:

```
[단식부기 - 가계부]
날짜       | 내용          | 수입    | 지출
2024-01-01 | 월급          | 300만원 |
2024-01-02 | 편의점        |         | 5,000원
→ 단순히 돈이 들어오고 나간 것만 기록

[복식부기 - 회계 장부]
날짜       | 차변(Debit)        | 대변(Credit)
2024-01-01 | 현금 300만원       | 급여수익 300만원
2024-01-02 | 식비 5,000원       | 현금 5,000원
→ 돈이 "어디에서 와서 어디로 갔는지" 양쪽 모두 기록
```

**핵심 원칙:** 모든 거래에서 `차변 합계 = 대변 합계`

이게 깨지면? → 어딘가에 오류가 있다는 것! (자기 검증 기능)

**비유:** 복식부기는 "모든 돈의 출발지와 도착지를 기록하는 GPS 추적기"

---

### Q2-1. 왜 복식부기를 도입했나요?

**모범 답변:**

이 시스템은 **공공재원(세금)**이 투입되는 지역화폐를 관리합니다. 단순히 잔액만 관리하면:

```
[단식부기 방식 - 잔액만 관리]
상품권 #1: balance = 50,000원
→ (결제 30,000원)
상품권 #1: balance = 20,000원

질문: "30,000원은 어디로 갔나요?" → 모름!
질문: "언제 차감되었나요?" → 모름!
질문: "어떤 가맹점에서 사용했나요?" → 별도 조회 필요!
질문: "혹시 버그로 잘못 차감된 건 아닌가요?" → 확인 불가!
```

```
[복식부기 방식 - 원장 기록]
LedgerEntry #1: DEBIT  MERCHANT_RECEIVABLE 30,000 (tx=7, 2024-01-15 14:30:22)
LedgerEntry #2: CREDIT VOUCHER_BALANCE     30,000 (tx=7, 2024-01-15 14:30:22)

답변: "30,000원은 가맹점 미수금으로 이동했습니다"
답변: "거래 #7에 의해 2024-01-15 14:30:22에 발생했습니다"
답변: "MERCHANT_RECEIVABLE 차변이므로 특정 가맹점으로 추적 가능합니다"
답변: "모든 원장의 합이 맞으므로 정확합니다"
```

**이 프로젝트의 코드:**

```kotlin
// LedgerService.kt - 모든 거래에서 호출됨
fun record(
    debitAccount: AccountCode,     // 차변 계정
    creditAccount: AccountCode,    // 대변 계정
    amount: BigDecimal,            // 금액
    transactionId: Long,           // 어떤 거래인지
    entryType: LedgerEntryType     // 구매/결제/취소/환불/만료
): List<LedgerEntry> {
    // 항상 2개를 동시에 생성! (하나만 생성되면 불균형!)
    val debitEntry = LedgerEntry(account = debitAccount, side = DEBIT, amount = amount, ...)
    val creditEntry = LedgerEntry(account = creditAccount, side = CREDIT, amount = amount, ...)
    return ledgerRepository.saveAll(listOf(debitEntry, creditEntry))
}
```

---

#### 꼬리질문 2-1-1: LedgerEntry가 @Immutable인 이유는?

**배경:**

```kotlin
@Entity
@Immutable  // ← Hibernate에게 "이 엔티티는 절대 수정/삭제하지 마!" 라고 선언
class LedgerEntry(...)
```

**금융 감사의 기본 원칙:**

```
"원장은 절대 수정하거나 삭제하지 않는다"
(A ledger entry, once written, must never be modified or deleted)
```

**왜?**

1. **법적 증거**: 금융 분쟁 시 원본 기록이 필요. 수정/삭제하면 증거 인멸
2. **감사 추적**: "이 돈이 왜 이동했는지" 추적하려면 모든 기록이 보존되어야 함
3. **무결성 검증**: 과거 기록이 바뀌면 `sum(DEBIT) == sum(CREDIT)` 검증 의미 없음

**그러면 잘못된 기록은 어떻게 수정하나요?**

→ 수정하지 않고 **반대 기록을 추가**합니다! (보상 트랜잭션)

```
원래 기록 (잘못됨):
  DEBIT MERCHANT_RECEIVABLE 30,000
  CREDIT VOUCHER_BALANCE 30,000

수정 방법: 반대 기록 추가
  DEBIT VOUCHER_BALANCE 30,000       ← 원래의 역방향
  CREDIT MERCHANT_RECEIVABLE 30,000  ← 원래의 역방향

결과: 두 기록이 상쇄되어 순잔액 = 0 (효과적으로 취소됨)
하지만 두 기록 모두 영원히 보존됨! → "왜 취소했는지" 추적 가능
```

---

#### 꼬리질문 2-1-2: 원장 기록과 잔액 업데이트의 원자성은 어떻게 보장하나요?

**초보자 설명:**

"원자성"이란 "전부 성공하거나, 전부 실패하거나" 둘 중 하나만 가능한 것입니다.

```
위험한 시나리오 (원자성 없음):
1. voucher.balance = 20,000원 저장 ✅
2. LedgerEntry 저장 ← 여기서 서버 다운! ❌

결과: 잔액은 줄었는데 원장에 기록이 없음!
→ 나중에 sum(DEBIT) ≠ sum(CREDIT) → 정합성 깨짐!
```

**이 프로젝트의 해결:**

```kotlin
// VoucherRedemptionService.kt
transactionTemplate.execute { _ ->          // ← DB 트랜잭션 시작
    voucher.redeem(amount)                  // 잔액 변경 (아직 커밋 안 됨)
    val tx = transactionService.create(...) // 거래 생성 (아직 커밋 안 됨)
    ledgerService.record(...)               // 원장 생성 (아직 커밋 안 됨)
    tx.complete()                           // 상태 변경 (아직 커밋 안 됨)
}                                          // ← 여기서 한꺼번에 COMMIT!
// 3개 중 하나라도 예외 → 전부 ROLLBACK!
```

**왜 이벤트(비동기)로 안 했나:**

```kotlin
// 위험한 방식 (비동기 이벤트)
@Transactional
fun redeem(...) {
    voucher.redeem(amount)
    // COMMIT → 잔액 변경 확정!
}

@TransactionalEventListener(phase = AFTER_COMMIT)
fun onRedeemed(event: ...) {
    // 별도 트랜잭션에서 원장 기록
    ledgerService.record(...)  // ← 여기서 실패하면? 원장 누락!
}
```

금융 시스템에서는 **"잔액 변경과 원장 기록은 반드시 같이 성공/실패"**해야 합니다.

---

#### 꼬리질문 2-1-3: `sum(DEBIT) == sum(CREDIT)` 검증은 언제 하나요?

**모범 답변:**

```kotlin
// LedgerVerificationService.kt
@Scheduled(cron = "0 0 2 * * *")  // 매일 새벽 2시
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
fun scheduledVerification() {
    val result = verify()
    if (!result.isBalanced) {
        log.error("LEDGER IMBALANCE DETECTED!")  // 🚨 CRITICAL 로그
    }
}
```

**검증 내용:**

```kotlin
fun verify(): VerificationResult {
    // 1단계: 전역 균형 검증
    val globalDebit = ledgerRepository.sumBySide(LedgerEntrySide.DEBIT)   // 모든 차변 합
    val globalCredit = ledgerRepository.sumBySide(LedgerEntrySide.CREDIT) // 모든 대변 합
    val globalBalanced = globalDebit.compareTo(globalCredit) == 0         // 같아야 함!

    // 2단계: 개별 상품권 검증
    val imbalanced = checkVoucherBalances()
    // 각 상품권의 balance 필드 vs 원장 계산값이 일치하는지
    
    return VerificationResult(
        isBalanced = globalBalanced && imbalanced.isEmpty(),
        ...
    )
}
```

**불일치 발견 시:**
- 자동 수정 ❌ (자동 수정하면 원인 은폐!)
- CRITICAL 감사 로그 생성
- 관리자 알림 (Slack, 이메일 등)
- 수동 조사 후 보정 분개(Adjusting Entry) 생성

**REPEATABLE_READ 격리 수준을 사용하는 이유:**
- 검증 중에 새로운 거래가 발생해도 일관된 스냅샷에서 검증
- "검증 시작 시점"의 데이터만 읽음 → Phantom Read 방지

---

### Q2-2. 계정과목(AccountCode)별 자금 흐름을 설명해주세요.

**모범 답변:**

```
[상품권 구매]
회원이 9만원 내고 10만원 상품권 구매:
  DEBIT  VOUCHER_BALANCE  100,000  (상품권 잔액 증가)
  CREDIT MEMBER_CASH      100,000  (회원 현금 감소)

[상품권 결제]
카페에서 3만원 결제:
  DEBIT  MERCHANT_RECEIVABLE 30,000  (가맹점이 받을 돈 증가)
  CREDIT VOUCHER_BALANCE     30,000  (상품권 잔액 감소)

[거래 취소]
위 결제를 취소:
  DEBIT  VOUCHER_BALANCE     30,000  (상품권 잔액 복원)
  CREDIT MERCHANT_RECEIVABLE 30,000  (가맹점이 받을 돈 감소)

[잔액 환불]
남은 2만원 현금 환불:
  DEBIT  REFUND_PAYABLE   20,000  (환불 지출 인식)
  CREDIT VOUCHER_BALANCE  20,000  (상품권 잔액 소멸)

[만료 처리]
유효기간 지남 (잔액도 0으로 설정됨):
  DEBIT  EXPIRED_VOUCHER  20,000  (만료 손실 인식)
  CREDIT VOUCHER_BALANCE  20,000  (상품권 잔액 소멸)

[정산 확정]
가맹점에 대금 지급 확정:
  DEBIT  SETTLEMENT_PAYABLE   30,000  (정산 지출 인식)
  CREDIT MERCHANT_RECEIVABLE  30,000  (가맹점 미수금 해소)
```

**외우는 팁:**
- DEBIT = "늘어나는 쪽" (자산이면) 또는 "책임이 생기는 쪽" (부채면)
- CREDIT = "줄어드는 쪽" (자산이면) 또는 "해소되는 쪽" (부채면)

---

## 3. 보상 트랜잭션 (Compensating Transaction)

### 배경 지식 (초보자용)

**왜 거래를 그냥 DELETE하면 안 되나요?**

일반 앱이라면 `DELETE FROM transactions WHERE id = 7` 하면 됩니다.
하지만 금융 시스템에서는:

```
감사관: "거래 #7은 왜 없나요?"
개발자: "취소되어서 삭제했습니다"
감사관: "그러면 어떤 내용의 거래가 왜 취소되었는지 어떻게 알 수 있나요?"
개발자: "...모릅니다"
감사관: "감사 부적격입니다" ❌

→ 기록 삭제 = 증거 인멸 = 법적 문제 가능!
```

**보상 트랜잭션의 원리:**

```
은행에서 수표를 잘못 발행했을 때:
❌ 수표를 찢어버림 → "왜 찢었는지" 기록 없음
✅ 수표에 "VOID" 도장 찍고 + "취소 전표" 발행 → 둘 다 보관!
```

---

### Q3-1. 거래 취소를 어떻게 구현했나요?

**모범 답변 (전체 흐름):**

```kotlin
// TransactionCancelService.kt
fun cancel(transactionId: Long): Long {
    // 1. 원본 거래 조회
    val original = transactionService.getById(transactionId)
    // original = Transaction(type=REDEMPTION, amount=30000, status=COMPLETED)
    
    val voucherId = original.voucherId!!
    
    return lockManager.withVoucherLock(voucherId) {
        transactionTemplate.execute { _ ->
            // 2. 원본 거래 상태 변경 (내용은 절대 수정 안 함!)
            original.requestCancel()
            // COMPLETED → CANCEL_REQUESTED
            
            // 3. 보상 거래 생성 (역방향)
            val compensating = transactionService.create(
                type = TransactionType.CANCELLATION,
                amount = original.amount,  // 같은 금액
                voucherId = voucherId,
                merchantId = original.merchantId,
                originalTransactionId = original.id,  // ⭐ 원본과 연결!
            )
            
            // 4. 역방향 원장 기록
            ledgerService.record(
                debitAccount = AccountCode.VOUCHER_BALANCE,       // 원래는 CREDIT이었음
                creditAccount = AccountCode.MERCHANT_RECEIVABLE,  // 원래는 DEBIT이었음
                amount = original.amount,
                transactionId = compensating.id,
                entryType = LedgerEntryType.CANCELLATION,
            )
            
            // 5. 보상 거래 완료 + 원본 거래 취소
            compensating.complete()  // PENDING → COMPLETED
            original.cancel()        // CANCEL_REQUESTED → CANCELLED
            
            // 6. 상품권 잔액 복원
            val voucher = voucherRepository.findByIdForUpdate(voucherId)!!
            voucher.restoreBalance(original.amount)
            // balance += 30000
            // 상태: EXHAUSTED → PARTIALLY_USED 또는 ACTIVE
            
            // 7. 감사 이벤트 발행
            eventPublisher.publishEvent(
                TransactionCancelledEvent(original.id, voucherId, original.amount)
            )
            
            compensating.id  // 보상 거래 ID 반환
        }!!
    }
}
```

**취소 전후 DB 상태:**

```
[취소 전]
transactions:
  #7: type=REDEMPTION, amount=30000, status=COMPLETED, voucherId=1

ledger_entries:
  #13: account=MERCHANT_RECEIVABLE, side=DEBIT,  amount=30000, txId=7
  #14: account=VOUCHER_BALANCE,     side=CREDIT, amount=30000, txId=7

vouchers:
  #1: balance=20000, status=PARTIALLY_USED

[취소 후]
transactions:
  #7: type=REDEMPTION, amount=30000, status=CANCELLED, voucherId=1  ← 상태만 변경!
  #8: type=CANCELLATION, amount=30000, status=COMPLETED, originalTxId=7  ← 새로 생성!

ledger_entries:
  #13: account=MERCHANT_RECEIVABLE, side=DEBIT,  amount=30000, txId=7  ← 그대로!
  #14: account=VOUCHER_BALANCE,     side=CREDIT, amount=30000, txId=7  ← 그대로!
  #15: account=VOUCHER_BALANCE,     side=DEBIT,  amount=30000, txId=8  ← 역방향!
  #16: account=MERCHANT_RECEIVABLE, side=CREDIT, amount=30000, txId=8  ← 역방향!

vouchers:
  #1: balance=50000, status=ACTIVE  ← 잔액 복원!
```

**검증:**
- MERCHANT_RECEIVABLE: DEBIT 30000 - CREDIT 30000 = 순잔액 0 ✅ (상쇄됨)
- VOUCHER_BALANCE: DEBIT 30000 - CREDIT 30000 = 순잔액 0 ✅ (발행 분 제외)
- 전체 DEBIT = 전체 CREDIT ✅ (균형 유지)

---

#### 꼬리질문 3-1-1: 이중 취소(같은 거래를 두 번 취소)를 방지하는 방법은?

**모범 답변:**

```kotlin
// Transaction.kt
fun requestCancel() {
    if (status != TransactionStatus.COMPLETED)  // ⭐ 핵심!
        throw BusinessException(ErrorCode.TRANSACTION_NOT_CANCELLABLE)
    status = TransactionStatus.CANCEL_REQUESTED
}
```

```
1차 취소: status = COMPLETED → requestCancel() → CANCEL_REQUESTED → cancel() → CANCELLED
2차 취소: status = CANCELLED → requestCancel() → "COMPLETED가 아님!" → 예외 발생!
```

**추가 방어 (동시 취소):**
분산락이 `voucher:{id}`로 걸리므로, 동시에 같은 거래를 취소해도 순차 처리됩니다.
- Thread A: 락 획득 → 취소 처리 → 커밋 → 락 해제
- Thread B: 락 획득 → 조회 → status = CANCELLED → 예외!

---

#### 꼬리질문 3-1-2: Saga 패턴과 보상 트랜잭션의 차이는?

**초보자 설명:**

**보상 트랜잭션** (이 프로젝트):
```
하나의 DB, 하나의 서비스 내에서 "이미 완료된 거래"를 논리적으로 되돌림
→ 같은 DB 트랜잭션 안에서 원자적으로 처리 가능
```

**Saga 패턴** (MSA 환경):
```
여러 서비스에 걸친 긴 트랜잭션을 관리

예: 주문 시스템
1. 주문 서비스: 주문 생성 ✅
2. 결제 서비스: 결제 처리 ✅
3. 재고 서비스: 재고 차감 ❌ (실패!)
→ 보상: 결제 서비스에 환불 요청 + 주문 서비스에 취소 요청

Choreography (안무): 각 서비스가 이벤트를 발행하고, 다음 서비스가 구독
Orchestration (지휘): 중앙 Saga Manager가 각 서비스에 명령
```

**이 프로젝트는 모놀리식이므로:**
- 모든 것이 하나의 DB 트랜잭션으로 처리 가능
- Saga 불필요 (과도한 복잡성)
- MSA 전환 시 Saga로 발전 가능

---

## 4. 도메인 설계 (DDD / State Machine)

### 배경 지식 (초보자용)

**상태 머신(State Machine)이란?**

자판기를 생각해보세요:
```
[대기 중] → (동전 투입) → [금액 표시 중] → (음료 선택) → [음료 배출 중] → [대기 중]
                                          → (취소 버튼) → [동전 반환 중] → [대기 중]

규칙:
- "대기 중"에서 바로 "음료 배출 중"으로 갈 수 없음 (돈 안 넣고 음료를 받을 수 없음!)
- 허용된 전이만 가능 → 이것이 상태 머신!
```

---

### Q4-1. Voucher 상태 머신을 설명해주세요.

**모범 답변:**

```
                    ┌─────────── 결제(일부) ────────────┐
                    │                                    │
                    ▼                                    │
┌──────┐     ┌──────────────┐     ┌──────────┐        │
│ACTIVE│────▶│PARTIALLY_USED│────▶│EXHAUSTED │        │
└──┬───┘     └──────┬───────┘     └──────────┘        │
   │                │                                   │
   │                │ 60%+사용                          │
   │                ▼                                   │
   │         ┌────────────────┐     ┌────────┐        │
   │         │REFUND_REQUESTED│────▶│REFUNDED│        │
   │         └────────────────┘     └────────┘        │
   │                                                   │
   │ 7일이내                                           │
   ▼                                                   │
┌──────────────────────┐     ┌─────────┐              │
│WITHDRAWAL_REQUESTED  │────▶│WITHDRAWN│              │
└──────────────────────┘     └─────────┘              │
                                                       │
   ┌─────────────────────────────────────────┐        │
   │ ACTIVE 또는 PARTIALLY_USED에서           │        │
   │ 유효기간 초과 시                          │        │
   │              ▼                           │        │
   │         ┌────────┐                      │        │
   │         │EXPIRED │                      │        │
   │         └────────┘                      │        │
   └─────────────────────────────────────────┘        │
                                                       │
   ┌──────────────────────────────────────────────────┘
   │ 거래 취소 시: 잔액 복원 → ACTIVE 또는 PARTIALLY_USED로 복원
```

**각 상태의 의미:**

| 상태 | 의미 | 가능한 다음 상태 |
|------|------|-----------------|
| `ACTIVE` | 발행됨, 미사용 | PARTIALLY_USED, EXHAUSTED, WITHDRAWAL_REQUESTED, EXPIRED |
| `PARTIALLY_USED` | 일부 사용됨 (잔액 있음) | EXHAUSTED, REFUND_REQUESTED, EXPIRED |
| `EXHAUSTED` | 전액 사용됨 (잔액 0) | (최종 상태) |
| `WITHDRAWAL_REQUESTED` | 청약철회 요청됨 | WITHDRAWN |
| `WITHDRAWN` | 청약철회 완료 | (최종 상태) |
| `REFUND_REQUESTED` | 잔액환불 요청됨 | REFUNDED |
| `REFUNDED` | 잔액환불 완료 | (최종 상태) |
| `EXPIRED` | 유효기간 만료 | (최종 상태) |

**코드에서의 구현:**

```kotlin
// Voucher.kt
fun redeem(amount: BigDecimal) {
    // 1. 전제조건 검증 (Guard Clause)
    if (!isUsable()) throw BusinessException(ErrorCode.VOUCHER_NOT_USABLE)
    if (isExpired()) throw BusinessException(ErrorCode.VOUCHER_EXPIRED)
    if (balance < amount) throw BusinessException(ErrorCode.INSUFFICIENT_BALANCE)

    // 2. 상태 변경
    balance -= amount
    status = if (balance.compareTo(BigDecimal.ZERO) == 0) 
        VoucherStatus.EXHAUSTED      // 잔액 0 → 소진
    else 
        VoucherStatus.PARTIALLY_USED  // 잔액 남음 → 부분 사용
}

fun isUsable(): Boolean = status in setOf(VoucherStatus.ACTIVE, VoucherStatus.PARTIALLY_USED)
// → EXHAUSTED, EXPIRED, REFUNDED, WITHDRAWN 상태에서는 결제 불가!
```

---

#### 꼬리질문 4-1-1: 상태 전이를 엔티티 메서드에 넣은 이유는? 서비스에서 해도 되지 않나요?

**모범 답변:**

```kotlin
// ❌ 나쁜 예: 서비스에서 상태 변경
class VoucherRedemptionService {
    fun redeem(voucherId: Long, amount: BigDecimal) {
        val voucher = repository.findById(voucherId)
        if (voucher.status != ACTIVE && voucher.status != PARTIALLY_USED) throw ...
        if (voucher.balance < amount) throw ...
        voucher.balance -= amount
        voucher.status = if (voucher.balance == 0) EXHAUSTED else PARTIALLY_USED
    }
}

class VoucherRefundService {
    fun refund(voucherId: Long) {
        val voucher = repository.findById(voucherId)
        if (voucher.status != PARTIALLY_USED) throw ...  // 중복!
        // ... 같은 검증 반복!
    }
}

// 문제점:
// 1. 검증 로직이 여러 서비스에 흩어짐 (DRY 원칙 위반)
// 2. 새 서비스 추가 시 검증 누락 가능
// 3. "상품권의 규칙"을 알려면 모든 서비스를 다 봐야 함
```

```kotlin
// ✅ 좋은 예: 엔티티에서 상태 변경 (Rich Domain Model)
class Voucher {
    fun redeem(amount: BigDecimal) {
        if (!isUsable()) throw ...  // 여기 한 곳에서만 검증!
        // ...
    }
    
    fun requestRefund(threshold: BigDecimal) {
        if (status != PARTIALLY_USED) throw ...  // 여기 한 곳에서만!
        // ...
    }
}

// 장점:
// 1. "상품권의 모든 규칙"이 Voucher.kt 한 파일에 모여 있음
// 2. 어떤 서비스에서 호출하든 반드시 검증을 거침
// 3. 새로운 개발자도 Voucher.kt만 읽으면 규칙 파악 가능
```

**이것이 DDD(Domain-Driven Design)의 핵심:**
- 도메인 규칙은 도메인 객체(Entity)가 스스로 지킨다
- 서비스는 "워크플로우 조율"만 담당
- 엔티티는 "자기 상태의 무결성"을 책임

---

#### 꼬리질문 4-1-2: 환불(Refund)과 청약철회(Withdrawal)의 차이는?

**초보자 설명 (비유):**

```
[청약철회 = "구매 후 마음이 바뀌었어요"]
- 옷을 사서 택을 안 뗐으면 7일 내 무조건 반품 가능
- 상품권을 사서 한 번도 안 썼으면 7일 내 무조건 환불

[잔액환불 = "많이 썼는데 잔액이 애매하게 남았어요"]  
- 10만원 상품권을 7만원 쓰고 3만원 남았을 때
- 3만원을 현금으로 돌려받는 것
- 단, 60% 이상 사용해야 가능 (소액 잔액이 사장되는 것 방지)
```

**코드 비교:**

```kotlin
// 청약철회: 7일 이내 + 미사용
fun requestWithdrawal(now: LocalDateTime = LocalDateTime.now()) {
    if (status != VoucherStatus.ACTIVE)          // ACTIVE만! (한 번이라도 쓰면 불가)
        throw BusinessException(ErrorCode.WITHDRAWAL_NOT_ALLOWED)
    if (purchasedAt.plusDays(7).isBefore(now))    // 7일 초과면 불가
        throw BusinessException(ErrorCode.WITHDRAWAL_PERIOD_EXPIRED)
    status = VoucherStatus.WITHDRAWAL_REQUESTED
}

fun completeWithdrawal(): BigDecimal {
    val refundAmount = balance  // 전액 환불! (= faceValue)
    balance = BigDecimal.ZERO
    status = VoucherStatus.WITHDRAWN
    return refundAmount
}

// 잔액환불: 60% 이상 사용
fun requestRefund(refundThresholdRatio: BigDecimal) {
    if (status != VoucherStatus.PARTIALLY_USED)  // 사용 중인 것만!
        throw BusinessException(ErrorCode.REFUND_CONDITION_NOT_MET)
    if (usageRatio < refundThresholdRatio)       // 60% 미만이면 불가
        throw BusinessException(ErrorCode.REFUND_CONDITION_NOT_MET)
    status = VoucherStatus.REFUND_REQUESTED
}

fun completeRefund(): BigDecimal {
    val refundAmount = balance  // 남은 잔액만 환불
    balance = BigDecimal.ZERO
    status = VoucherStatus.REFUNDED
    return refundAmount
}
```

**비교표:**

| 구분 | 청약철회 | 잔액환불 |
|------|---------|---------|
| 대상 | ACTIVE (미사용) | PARTIALLY_USED (사용 중) |
| 기간 제한 | 7일 이내 | 없음 |
| 사용량 조건 | 없음 (미사용만) | 60% 이상 사용 |
| 환불 금액 | 전액 (faceValue) | 잔액만 (balance) |
| 법적 근거 | 전자상거래법 | 지역화폐 조례 |
| 소비자 권리 | 무조건적 | 조건부 |

---

### Q4-2. `usageRatio`는 어떻게 계산되나요?

**모범 답변:**

```kotlin
val usageRatio: BigDecimal
    get() = (faceValue - balance).divide(faceValue, 4, RoundingMode.HALF_UP)

// 예시:
// faceValue = 50,000원, balance = 20,000원
// usageRatio = (50,000 - 20,000) / 50,000 = 30,000 / 50,000 = 0.6000 (60%)
```

**경계값 테스트 (BoundaryTest.kt):**

```kotlin
// 정확히 60% 사용 → 환불 가능
// 50,000원 중 30,000원 사용 = 60%
@Test
fun `should allow refund when usage is exactly 60 percent`() {
    val voucher = fixtures.issueVoucher(memberId, regionId, BigDecimal("50000"))
    redemptionService.redeem(voucher.id, merchantId, BigDecimal("30000"))
    val refunded = refundService.refund(voucher.id, memberId)
    refunded.status shouldBe VoucherStatus.REFUNDED  // ✅ 성공
}

// 59% 사용 → 환불 불가
// 50,000원 중 29,500원 사용 = 59%
@Test
fun `should reject refund when usage is 59 percent`() {
    val voucher = fixtures.issueVoucher(memberId, regionId, BigDecimal("50000"))
    redemptionService.redeem(voucher.id, merchantId, BigDecimal("29500"))
    shouldThrow<BusinessException> {
        refundService.refund(voucher.id, memberId)
    }.errorCode shouldBe ErrorCode.REFUND_CONDITION_NOT_MET  // ❌ 거절
}
```

---

## 5. 이벤트 & 감사 로그 (Event / Audit)

### 배경 지식 (초보자용)

**감사 로그(Audit Log)란?**

CCTV 같은 것입니다. "누가, 언제, 무엇을, 왜 했는지"를 기록합니다.

```
일반 시스템: 로그 = "에러 발생 시 디버깅용"
금융 시스템: 감사 로그 = "법적 의무. 금감원 감사 시 제출해야 함"
```

**이벤트(Event)란?**

"뭔가 일어났다!"는 알림입니다.

```
실생활 예: 택배 알림
  "주문이 접수되었습니다" → 이벤트
  "배송이 시작되었습니다" → 이벤트
  "배송이 완료되었습니다" → 이벤트

이 시스템:
  "상품권이 발행되었습니다" → VoucherIssuedEvent
  "상품권이 결제되었습니다" → VoucherRedeemedEvent
  "거래가 취소되었습니다" → TransactionCancelledEvent
```

---

### Q5-1. 감사 이벤트의 심각도별 처리 전략을 설명해주세요.

**모범 답변:**

```kotlin
// AuditEventListener.kt

// CRITICAL 이벤트: 비즈니스 트랜잭션 커밋 "전"에 처리
@TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
fun handleCriticalAudit(event: DomainEvent) {
    val severity = resolveSeverity(event.eventType)
    if (severity != AuditSeverity.CRITICAL) return
    saveAuditLog(event, severity)  // 실패 시 → 전체 롤백!
}

// HIGH/MEDIUM 이벤트: 비즈니스 트랜잭션 커밋 "후"에 별도 트랜잭션
@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
@Transactional(propagation = Propagation.REQUIRES_NEW)
fun handleNonCriticalAudit(event: DomainEvent) {
    val severity = resolveSeverity(event.eventType)
    if (severity == AuditSeverity.CRITICAL) return
    try {
        saveAuditLog(event, severity)
    } catch (e: Exception) {
        saveFailedEvent(event, e)  // 실패해도 비즈니스 TX는 이미 성공
    }
}
```

**시각화:**

```
[CRITICAL 이벤트 - 결제/발행/환불/취소]

┌─── DB 트랜잭션 ──────────────────────────────────────┐
│                                                       │
│  1. voucher.redeem(30000)                            │
│  2. INSERT Transaction                                │
│  3. INSERT LedgerEntry × 2                           │
│  4. tx.complete()                                    │
│                                                       │
│  ─── BEFORE_COMMIT ───                               │
│  5. INSERT AuditLog (CRITICAL)  ← 여기서 실패하면?   │
│     → 1~4도 전부 ROLLBACK!                           │
│  ─── COMMIT ───                                      │
│                                                       │
└───────────────────────────────────────────────────────┘

결과: "감사 로그 없는 거래"는 절대 존재하지 않음!
```

```
[HIGH/MEDIUM 이벤트 - 가맹점 승인, 회원 정지 등]

┌─── DB 트랜잭션 1 ───────────┐
│  1. merchant.approve()       │
│  2. COMMIT ✅                │
└──────────────────────────────┘
          │
          ▼ (AFTER_COMMIT: 커밋 완료 후 실행)
┌─── DB 트랜잭션 2 (별도) ────┐
│  3. INSERT AuditLog (HIGH)   │
│  실패하면? → FailedEvent에 저장│
│  비즈니스 TX는 이미 성공!    │
└──────────────────────────────┘
```

**왜 이렇게 나누나:**
- CRITICAL: "기록 없는 거래"는 허용 불가. 감사 실패 = 비즈니스 실패
- HIGH/MEDIUM: 비즈니스가 성공했는데 감사 로그 때문에 롤백하면 안 됨. 나중에 재시도

---

#### 꼬리질문 5-1-1: FailedEvent 테이블은 왜 필요한가요?

**모범 답변:**

```kotlin
// 시나리오: HIGH 이벤트 감사 로그 저장 실패
@TransactionalEventListener(phase = AFTER_COMMIT)
fun handleNonCriticalAudit(event: DomainEvent) {
    try {
        saveAuditLog(event, severity)  // DB 일시적 장애로 실패!
    } catch (e: Exception) {
        // 이벤트 데이터를 FailedEvent 테이블에 저장
        saveFailedEvent(event, e)
        // → 나중에 스케줄러가 재시도
    }
}
```

**FailedEvent = "실패한 이벤트의 임시 보관함"**

```
정상 흐름:
  이벤트 발생 → 감사 로그 저장 ✅

비정상 흐름:
  이벤트 발생 → 감사 로그 저장 실패 ❌ → FailedEvent에 저장
  → (5분 후 스케줄러) → FailedEvent 조회 → 감사 로그 재시도 ✅ → FailedEvent 삭제
```

이것은 **Transactional Outbox Pattern**의 변형입니다:
- 원본 이벤트가 유실되지 않도록 DB에 영구 저장
- 비동기로 재처리하여 최종적 일관성(Eventual Consistency) 보장

---

## 6. 테스트 전략 (Testing Strategy)

### 배경 지식 (초보자용)

**테스트 피라미드:**

```
         /\
        /  \        E2E 테스트 (적지만 비용 높음)
       / UI \       - 전체 시스템 통합 검증
      /──────\
     /        \     통합 테스트 (중간)
    / Service  \    - DB, Redis 포함 서비스 레이어 검증
   /────────────\
  /              \   단위 테스트 (많지만 비용 낮음)
 /    Domain      \  - 외부 의존 없이 비즈니스 로직만 검증
/──────────────────\
```

**이 프로젝트의 테스트 구성:**
- 단위 테스트 41개: Voucher, Member, Merchant, Region 상태 머신
- 통합 테스트 21개: E2E, 동시성, 경계값, 만료, 원장

---

### Q6-1. Testcontainers를 사용한 이유는? H2로 충분하지 않나요?

**배경:**

```
H2 = 자바로 만든 경량 DB (메모리에서 동작)
MySQL = 실제 운영 환경 DB

문제: H2와 MySQL은 다르게 동작하는 부분이 있음!
```

**실제로 다른 부분들:**

| 기능 | H2 | MySQL |
|------|-----|-------|
| JSON 컬럼 | 지원 안 됨 | `@JdbcTypeCode(SqlTypes.JSON)` 사용 중 |
| `SELECT FOR UPDATE` | 제한적 동작 | 행 수준 배타적 잠금 |
| 트랜잭션 격리 수준 | 기본 READ_COMMITTED | 기본 REPEATABLE_READ |
| 인덱스 동작 | B-Tree 유사 | 실제 InnoDB B+Tree |
| 문자열 비교 | 대소문자 구분 | collation에 따라 다름 |

**"H2에서 통과한 테스트가 MySQL에서 실패"하는 실제 사례:**

```
사례 1: JSON 컬럼
  - AuditLog의 previousState, currentState가 JSON 타입
  - H2에서는 TEXT로 대체 → 쿼리 동작이 다름

사례 2: Pessimistic Lock
  - H2의 FOR UPDATE는 테이블 수준 잠금 (행 수준이 아님!)
  - 동시성 테스트가 H2에서는 무의미

사례 3: 트랜잭션 격리
  - LedgerVerificationService가 REPEATABLE_READ 사용
  - H2에서는 MySQL과 다르게 동작할 수 있음
```

**Testcontainers란:**

```kotlin
// IntegrationTestSupport.kt (테스트 부모 클래스)
@SpringBootTest
@Testcontainers
abstract class IntegrationTestSupport {
    companion object {
        @Container
        val mysql = MySQLContainer("mysql:8.0")  // 진짜 MySQL 8.0 도커 컨테이너!
        
        @Container
        val redis = GenericContainer("redis:7-alpine")  // 진짜 Redis 7!
    }
}

// 테스트 시작 시:
// 1. Docker로 MySQL 8.0 컨테이너 자동 시작 (10초)
// 2. Docker로 Redis 7 컨테이너 자동 시작 (3초)
// 3. 테스트 실행 (실제 DB/Redis 사용)
// 4. 테스트 종료 시 컨테이너 자동 정리
```

---

### Q6-2. 동시성 테스트는 어떻게 작성했나요?

**완전한 설명:**

```kotlin
@Test
fun `10 concurrent redemptions on same voucher should not over-deduct`() {
    // === Given: 테스트 데이터 준비 ===
    val voucher = fixtures.issueVoucher(memberId, regionId, BigDecimal("50000"))
    // 50,000원 상품권 1개 발행

    // === When: 10개 스레드 동시 실행 ===
    val threadCount = 10
    val latch = CountDownLatch(1)  // "출발 신호총"
    val executor = Executors.newFixedThreadPool(threadCount)
    val successCount = AtomicInteger(0)  // 스레드 안전한 카운터
    val failCount = AtomicInteger(0)

    val futures = (1..threadCount).map {
        executor.submit {
            latch.await()  // 모든 스레드가 여기서 대기! ("준비~")
            try {
                redemptionService.redeem(voucher.id, merchantId, BigDecimal("10000"))
                successCount.incrementAndGet()
            } catch (e: BusinessException) {
                failCount.incrementAndGet()
            }
        }
    }
    
    latch.countDown()  // "출발!" → 10개 스레드 동시 시작
    futures.forEach { it.get() }  // 모든 스레드 완료 대기
    executor.shutdown()

    // === Then: 결과 검증 ===
    val updated = voucherRepository.findById(voucher.id).get()
    
    // I1: 잔액은 절대 음수가 되면 안 됨!
    updated.balance shouldBeGreaterThanOrEqualTo BigDecimal.ZERO
    
    // 50,000 / 10,000 = 정확히 5건만 성공해야 함
    successCount.get() shouldBe 5
    failCount.get() shouldBe 5
    updated.balance.compareTo(BigDecimal.ZERO) shouldBe 0  // 잔액 = 0
    
    // 실패 원인이 비즈니스 로직(잔액 부족)이어야 함 (락 타임아웃 아님!)
    failReasons.keys shouldBe setOf("INSUFFICIENT_BALANCE")
    
    // I2: 원장 균형 유지
    verificationService.verify().isBalanced shouldBe true
}
```

**CountDownLatch가 하는 일 (비유):**

```
운동회 100m 달리기:
  선수 10명이 출발선에 서 있음 (latch.await())
  심판이 "탕!" (latch.countDown())
  → 10명이 동시에 출발!

코드:
  Thread 1~10: latch.await()에서 대기
  메인 스레드: latch.countDown() 호출
  → Thread 1~10이 거의 동시에 redeem() 호출!
```

**AtomicInteger를 쓰는 이유:**

```kotlin
// ❌ 위험: 일반 변수
var count = 0
// Thread A: count 읽기(0) + 1 = 1 → 저장
// Thread B: count 읽기(0) + 1 = 1 → 저장 (A의 변경을 못 봄!)
// 결과: count = 1 (2여야 하는데!)

// ✅ 안전: AtomicInteger
val count = AtomicInteger(0)
count.incrementAndGet()  // 원자적 연산 (중간에 끊기지 않음)
```

---

## 7. 멱등성 (Idempotency)

### 배경 지식 (초보자용)

**멱등성이란?**

```
"같은 요청을 100번 보내도 결과가 1번 보낸 것과 같다"

실생활 예:
- 엘리베이터 버튼: 3번 눌러도 1번만 동작 → 멱등
- 물 끓이기: 1번 끓이나 3번 끓이나 결과 같음 → 멱등
- 은행 이체: 같은 요청 3번 보내면 3번 이체됨 → 멱등 ❌ (문제!)

HTTP 메서드:
- GET: 멱등 (여러 번 조회해도 결과 같음)
- DELETE: 멱등 (이미 삭제된 것을 또 삭제해도 OK)
- POST: 멱등 ❌ (매번 새 리소스 생성!)
```

**금융에서 멱등성이 왜 중요한가:**

```
시나리오: 결제 요청을 보냈는데 네트워크 끊김

Client → [POST /redeem] → Server: 처리 완료! → [응답] → X (네트워크 끊김)
Client: "응답 못 받았으니 실패한 것 같아. 다시 보내자!"
Client → [POST /redeem] → Server: ???

멱등성 없으면: 또 30,000원 차감! → 이중 결제!!! 💸
멱등성 있으면: "이미 처리한 요청이네요. 이전 결과를 돌려드릴게요." → 안전! ✅
```

---

### Q7-1. 멱등키를 어떻게 처리하나요?

**모범 답변:**

```
클라이언트 요청:
  POST /api/v1/vouchers/123/redeem
  Headers:
    X-Idempotency-Key: "550e8400-e29b-41d4-a716-446655440000"  ← UUID (클라이언트 생성)
  Body:
    { "merchantId": 5, "amount": 30000 }
```

**처리 흐름:**

```
┌─ 요청 도착 ─────────────────────────────────────────────────┐
│                                                              │
│  1. Redis에서 멱등키 검색                                     │
│     → 있으면: 저장된 응답 반환 (HTTP 200 + 이전 결과)         │
│     → 없으면: 계속 진행                                      │
│                                                              │
│  2. (Redis에 없을 때) DB에서 멱등키 검색                      │
│     → 있으면: 저장된 응답 반환                                │
│     → 없으면: 계속 진행                                      │
│                                                              │
│  3. 비즈니스 로직 실행 (실제 결제)                             │
│                                                              │
│  4. 결과를 Redis에 저장 (TTL: 24시간)                        │
│     Key: "idempotency:550e8400-..."                          │
│     Value: { statusCode: 200, body: { txId: 42, balance: 20000 } }
│                                                              │
│  5. 클라이언트에 응답 반환                                    │
└──────────────────────────────────────────────────────────────┘

같은 멱등키로 재요청:
  → 1번 단계에서 Redis에서 발견 → 이전 응답 그대로 반환 (결제 재실행 안 함!)
```

**왜 Redis + DB 이중 저장?**

| 상황 | Redis만 | Redis + DB |
|------|---------|------------|
| 정상 | Redis에서 즉시 조회 (0.1ms) | 동일 |
| Redis TTL 만료 (24h 후) | 멱등성 깨짐! | DB에서 2차 검증 |
| Redis 재시작 | 멱등성 깨짐! | DB에서 2차 검증 |
| 네트워크 장애 | 멱등성 깨짐! | DB에서 2차 검증 |

---

#### 꼬리질문 7-1-1: 클라이언트가 멱등키를 안 보내면?

**모범 답변:**

금전 관련 엔드포인트(구매/결제/환불/철회/취소)에는 **필수**입니다.

```
X-Idempotency-Key 헤더 없이 요청:
→ 400 Bad Request: "멱등키가 필요합니다"
```

클라이언트 SDK에서 자동 생성하도록 가이드:
```javascript
// 프론트엔드 SDK 예시
async function redeemVoucher(voucherId, amount) {
    const idempotencyKey = crypto.randomUUID(); // 자동 생성
    return fetch(`/api/v1/vouchers/${voucherId}/redeem`, {
        method: 'POST',
        headers: { 'X-Idempotency-Key': idempotencyKey },
        body: JSON.stringify({ amount }),
    });
}
```

---

## 8. 아키텍처 & 설계 원칙

### Q8-1. 패키지 구조를 설명해주세요.

**모범 답변:**

```
com.commerce/
├── common/              ← 모든 도메인이 공유하는 것
│   ├── domain/          BaseEntity (ID, 생성일, 수정일, 버전)
│   ├── exception/       ErrorCode, BusinessException
│   ├── audit/           AuditLog, AuditEventListener
│   └── idempotency/     Idempotent annotation, Interceptor
│
├── region/              ← 지자체 도메인
│   ├── domain/          Region, RegionPolicy, RegionStatus
│   ├── application/     RegionService
│   ├── infrastructure/  RegionJpaRepository, RegionQueryRepository
│   └── interfaces/      RegionController, DTOs
│
├── member/              ← 회원 도메인
│   ├── domain/          Member, MemberRole, MemberStatus
│   ├── application/     MemberService
│   ├── infrastructure/  MemberJpaRepository
│   └── interfaces/      MemberController, DTOs
│
├── merchant/            ← 가맹점 + 정산 도메인
│   ├── domain/          Merchant, Settlement, Events
│   ├── application/     MerchantService, SettlementService
│   ├── infrastructure/  MerchantJpaRepository, SettlementJpaRepository
│   └── interfaces/      MerchantController, SettlementController
│
├── voucher/             ← ⭐ 핵심 도메인
│   ├── domain/          Voucher, VoucherStatus, VoucherCodeGenerator, Events
│   ├── application/     IssueService, RedemptionService, RefundService, 
│   │                    WithdrawalService, ExpiryScheduler
│   ├── infrastructure/  VoucherJpaRepository, VoucherQueryRepository, 
│   │                    VoucherLockManager
│   └── interfaces/      VoucherController, DTOs
│
├── transaction/         ← 거래 도메인
│   ├── domain/          Transaction, TransactionType, TransactionStatus, Events
│   ├── application/     TransactionService, TransactionCancelService
│   ├── infrastructure/  TransactionJpaRepository
│   └── interfaces/      TransactionController
│
├── ledger/              ← 원장 도메인
│   ├── domain/          LedgerEntry, AccountCode, LedgerEntrySide, LedgerEntryType
│   ├── application/     LedgerService, LedgerVerificationService
│   ├── infrastructure/  LedgerJpaRepository
│   └── interfaces/      LedgerController
│
└── config/              ← 설정
    ├── SecurityConfig   JWT + Spring Security
    ├── RedisConfig      Redisson 클라이언트
    ├── QueryDslConfig   JPAQueryFactory
    └── SwaggerConfig    OpenAPI 3.0
```

**이 구조의 이름:** Package by Feature (기능별 패키지)

**장점:**
1. `voucher/` 폴더만 보면 상품권 관련 **모든 것** 파악 가능
2. 각 도메인이 독립적 → MSA 분리 시 패키지 단위로 추출
3. 새 기능 추가 시 하나의 패키지만 수정

**각 레이어의 역할:**

```
interfaces/   ← "외부와 대화" (HTTP 요청 받기, 응답 보내기)
    │
    ▼
application/ ← "워크플로우 조율" (서비스 호출 순서 정리)
    │
    ▼
domain/      ← "비즈니스 규칙" (상태 전이, 검증, 계산)
    │
    ▼
infrastructure/ ← "기술적 세부사항" (DB 저장, Redis 연결)
```

**의존 방향:** interfaces → application → domain ← infrastructure
(domain은 아무것도 의존하지 않음 = 가장 안정적!)

---

#### 꼬리질문 8-1-1: 이 구조를 Hexagonal Architecture로 바꾼다면?

**초보자 설명:**

```
현재 구조 (Layered within Feature):
  Controller → Service → Repository(인터페이스) = infrastructure에 위치
  
Hexagonal Architecture (포트와 어댑터):
  Controller(Adapter) → UseCase(Port) ← Service(구현) → Repository Port ← JPA Adapter
```

**차이:**

```kotlin
// 현재: Service가 Repository를 직접 사용
class VoucherRedemptionService(
    private val voucherRepository: VoucherJpaRepository,  // 구체 클래스 의존
)

// Hexagonal: Service가 Port(인터페이스)에 의존
class VoucherRedemptionService(
    private val voucherPort: VoucherPort,  // 인터페이스에 의존
)

// Port는 domain 패키지에 위치
interface VoucherPort {
    fun findByIdForUpdate(id: Long): Voucher?
    fun save(voucher: Voucher): Voucher
}

// Adapter(구현체)는 infrastructure 패키지에 위치  
class VoucherJpaAdapter(
    private val jpaRepository: VoucherJpaRepository
) : VoucherPort {
    override fun findByIdForUpdate(id: Long) = jpaRepository.findByIdForUpdate(id)
}
```

**현재 구조를 유지하는 이유:**
- 모놀리식 초기 단계에서 Hexagonal은 "인터페이스 하나에 구현체 하나" → 보일러플레이트만 증가
- Repository가 1개의 구현체만 가질 때 인터페이스를 분리하는 것은 과도한 추상화
- MSA 전환 시 그때 Port/Adapter로 분리해도 늦지 않음

---

## 9. Spring Boot / JPA 심화

### Q9-1. `open-in-view: false`로 설정한 이유는?

**초보자 설명:**

OSIV (Open Session In View)란?

```
OSIV = true (기본값):
  HTTP 요청 시작 ─────────────── DB 세션 열림 ───────────────── HTTP 응답 끝
  │                                                               │
  │  Controller    Service    Repository                          │
  │  ────────     ─────────  ──────────                          │
  │  여기서도     여기서도    여기서도                              │
  │  DB 접근     DB 접근     DB 접근                              │
  │  가능!       가능!       가능!                                │
  └───────────────────────────────────────────────────────────────┘

OSIV = false:
  HTTP 요청 시작 ────────────────────────────────── HTTP 응답 끝
  │                                                  │
  │  Controller    │  Service(@Transactional)  │     │
  │  ────────      │  ─────── DB 세션 ────── │     │
  │  DB 접근 ❌   │  DB 접근 ✅             │     │
  │  (Lazy Load   │                          │     │
  │   Exception!) │                          │     │
  └────────────────────────────────────────────────┘
```

**왜 false로 했나:**

```yaml
# application.yml
spring:
  jpa:
    open-in-view: false
```

1. **커넥션 점유 시간 최소화**: 
   - true: 요청 전체(예: 500ms) 동안 DB 커넥션 1개 점유
   - false: 서비스 실행(예: 50ms) 동안만 점유
   - 동시 요청 100개: true면 커넥션 100개 필요, false면 10개로 충분

2. **트랜잭션 경계 명확화**:
   - 금융 시스템에서 "어디서 DB를 읽고 쓰는지" 명확해야 함
   - Controller에서 의도치 않은 DB 접근 방지

3. **N+1 문제 조기 발견**:
   - true면 Controller에서 Lazy Loading이 되므로 N+1을 모르고 넘어감
   - false면 서비스 계층에서 미리 fetch 해야 하므로 의식적으로 해결

---

### Q9-2. Kotlin과 JPA를 함께 사용할 때 주의점은?

**모범 답변:**

**1. all-open 플러그인:**

```kotlin
// build.gradle.kts
plugins {
    kotlin("plugin.allopen")
}
allOpen {
    annotation("jakarta.persistence.Entity")
    annotation("jakarta.persistence.MappedSuperclass")
    annotation("jakarta.persistence.Embeddable")
}
```

왜 필요한가:
```kotlin
// Kotlin 클래스는 기본적으로 final
class Voucher { ... }  // = final class Voucher (Java)

// JPA(Hibernate)는 프록시를 만들기 위해 상속이 필요
class VoucherProxy extends Voucher { ... }  // ❌ final이라 상속 불가!

// all-open 플러그인이 @Entity 클래스를 open으로 변경
open class Voucher { ... }  // → 프록시 생성 가능!
```

**2. no-arg 플러그인:**

```kotlin
// JPA는 리플렉션으로 객체를 생성하므로 기본 생성자 필요
// Kotlin 클래스에 val/var 속성이 있으면 기본 생성자가 없음!

class Voucher(
    val voucherCode: String,  // 생성 시 반드시 값 필요
    val faceValue: BigDecimal,
)
// → 기본 생성자(인자 없는) 없음!

// kotlin-jpa 플러그인이 자동으로 기본 생성자 생성 (바이트코드 레벨)
```

**3. data class를 Entity로 쓰면 안 되는 이유:**

```kotlin
// ❌ 위험한 방식
data class Voucher(val id: Long, val balance: BigDecimal, ...)

// 문제 1: equals/hashCode가 모든 필드를 비교
val v1 = repository.findById(1)  // 프록시 객체
val v2 = repository.findById(1)  // 같은 엔티티
v1 == v2  // false일 수 있음! (프록시 vs 실제 객체)

// 문제 2: copy()로 ID가 0인 새 객체 생성 가능
val v3 = v1.copy(balance = BigDecimal.ZERO)  // v3.id = 0? → DB에서 혼란

// ✅ 올바른 방식: 일반 class 사용
class Voucher(val id: Long, var balance: BigDecimal, ...)
```

**4. val vs var 규칙:**

```kotlin
class Voucher(
    val voucherCode: String,     // 절대 변하지 않음 → val
    val faceValue: BigDecimal,   // 절대 변하지 않음 → val
    var balance: BigDecimal,     // JPA가 업데이트해야 함 → var
    var status: VoucherStatus,   // JPA가 업데이트해야 함 → var
)
```

---

### Q9-3. `@GeneratedValue(strategy = IDENTITY)`의 한계는?

**초보자 설명:**

```kotlin
@Id
@GeneratedValue(strategy = GenerationType.IDENTITY)
val id: Long = 0L
```

이것은 MySQL의 AUTO_INCREMENT를 사용합니다:
```sql
INSERT INTO vouchers (...) VALUES (...)  -- ID 미지정
-- MySQL이 자동으로 ID 부여 (1, 2, 3, ...)
-- INSERT 후에야 ID를 알 수 있음!
```

**한계: Batch INSERT 불가**

```kotlin
// 1000개 상품권을 한 번에 저장하고 싶을 때
val vouchers = (1..1000).map { Voucher(...) }
repository.saveAll(vouchers)

// IDENTITY 전략에서:
// INSERT INTO vouchers VALUES (...)  ← 1건
// SELECT LAST_INSERT_ID()            ← ID 조회
// INSERT INTO vouchers VALUES (...)  ← 1건
// SELECT LAST_INSERT_ID()            ← ID 조회
// ... 2000번 왕복! (느림!)

// Batch INSERT가 가능했다면:
// INSERT INTO vouchers VALUES (...), (...), (...), ...  ← 1번에 1000건!
```

**왜 이 프로젝트에서는 괜찮은가:**
- 상품권 발행은 한 번에 1건씩 (대량 발행 시나리오 없음)
- 단일 DB 환경에서 AUTO_INCREMENT가 가장 간단하고 안정적

**대안 (대량 처리 필요 시):**
- `SEQUENCE` 전략 (MySQL은 미지원)
- `TABLE` 전략 (시퀀스 테이블 사용)
- UUID (인덱스 성능 저하 주의)
- TSID/Snowflake (분산 환경용)

---

## 10. 보안 (Security)

### Q10-1. JWT 인증을 어떻게 구현했나요?

**초보자 설명:**

JWT (JSON Web Token) = "디지털 신분증"

```
로그인 시:
  Client → POST /login { email, password } → Server
  Server: "확인됐습니다. 이 토큰을 가지고 다니세요."
  Server → { token: "eyJhbGciOi..." } → Client

이후 모든 요청:
  Client → GET /vouchers, Headers: { Authorization: "Bearer eyJhbGciOi..." }
  Server: "토큰 확인됨. 회원 #7이군요." → 처리
```

**JWT 구조:**
```
eyJhbGciOiJIUzI1NiJ9.eyJtZW1iZXJJZCI6NywiZXhwIjoxNzA5MjQ1MDIzfQ.abc123...
  ↑ Header(암호화 알고리즘)  ↑ Payload(데이터)                  ↑ Signature(서명)

Payload 내용:
{
  "memberId": 7,
  "role": "USER",
  "exp": 1709245023  // 만료 시간 (24시간 후)
}
```

**이 프로젝트의 SecurityConfig:**

```kotlin
@Configuration
class SecurityConfig {
    @Bean
    fun securityFilterChain(http: HttpSecurity): SecurityFilterChain {
        http
            .csrf { it.disable() }  // REST API이므로 CSRF 불필요
            .sessionManagement { 
                it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) 
                // 세션 사용 안 함 (JWT가 상태를 가지고 다님)
            }
            .authorizeHttpRequests {
                it.requestMatchers("/api/v1/members/register", "/api/v1/members/login")
                    .permitAll()  // 회원가입/로그인은 인증 없이 접근
                it.requestMatchers("/actuator/**", "/swagger-ui/**")
                    .permitAll()  // 모니터링/문서도 공개
                it.anyRequest().permitAll()  // 포트폴리오용: 전체 허용
                // 프로덕션: it.anyRequest().authenticated()
            }
        return http.build()
    }
    
    @Bean
    fun passwordEncoder(): PasswordEncoder = BCryptPasswordEncoder()
    // 비밀번호를 BCrypt로 해시 (복호화 불가)
}
```

---

#### 꼬리질문 10-1-1: JWT의 단점과 보완 방법은?

**모범 답변:**

| 단점 | 설명 | 보완 방법 |
|------|------|----------|
| 토큰 무효화 불가 | 발급 후 24시간 동안 유효. 회원 탈퇴/정지해도 토큰으로 접근 가능 | Token Blacklist (Redis에 무효 토큰 저장) |
| 크기 | 클레임이 많을수록 토큰이 커짐 (매 요청에 포함) | 필수 정보만 담기 (ID, role) |
| 비밀키 유출 시 | 모든 토큰 위조 가능 | 키 로테이션 (JWKS), RSA 키 쌍 사용 |
| 갱신 문제 | 만료 시 재로그인 필요 | Refresh Token 패턴 |

**Refresh Token 패턴:**

```
Access Token: 짧은 수명 (15분)
Refresh Token: 긴 수명 (7일, DB에 저장)

1. 로그인 → Access Token(15분) + Refresh Token(7일) 발급
2. API 호출 → Access Token 사용
3. Access Token 만료 → Refresh Token으로 새 Access Token 발급
4. Refresh Token 만료 → 재로그인 필요
5. 로그아웃/탈퇴 → Refresh Token DB에서 삭제 → 갱신 불가 → 15분 후 접근 불가
```

---

## 11. 성능 & 모니터링

### Q11-1. 어떤 메트릭을 수집하고 있나요?

**초보자 설명:**

메트릭(Metric) = "시스템의 건강 지표"

```
사람의 건강 지표: 체온, 혈압, 맥박
시스템의 건강 지표: 응답시간, 에러율, 처리량
```

**이 프로젝트의 메트릭:**

```kotlin
// VoucherRedemptionService.kt
fun redeem(...): RedemptionResult {
    return lockManager.withVoucherLock(voucherId) {
        val timer = Timer.start(meterRegistry)  // ⏱ 시작!
        try {
            // 비즈니스 로직...
            meterRegistry.counter("voucher.redemption.count", "result", "success").increment()
            // "결제 성공" 카운터 +1
        } catch (e: Exception) {
            meterRegistry.counter("voucher.redemption.count", "result", "failure").increment()
            // "결제 실패" 카운터 +1
            throw e
        } finally {
            timer.stop(meterRegistry.timer("voucher.redemption.duration"))
            // ⏱ 종료! 소요시간 기록
        }
    }
}

// VoucherLockManager.kt
private fun <T> withLock(key: String, action: () -> T): T {
    val timer = Timer.start(meterRegistry)
    val acquired = lock.tryLock(5, 10, TimeUnit.SECONDS)
    timer.stop(meterRegistry.timer("lock.acquisition.duration", "key", key.substringBefore(':')))
    // "락 획득에 걸린 시간" 기록

    if (!acquired) {
        meterRegistry.counter("lock.acquisition.timeout", "key", key.substringBefore(':')).increment()
        // "락 타임아웃 횟수" +1
    }
}
```

**수집되는 메트릭 목록:**

| 메트릭 | 의미 | 경고 기준 (예) |
|--------|------|---------------|
| `voucher.redemption.count{result=success}` | 결제 성공 수 | 0이 지속되면 장애 |
| `voucher.redemption.count{result=failure}` | 결제 실패 수 | 급증하면 이상 |
| `voucher.redemption.duration` | 결제 처리 시간 | p99 > 3초면 경고 |
| `lock.acquisition.duration` | 락 획득 시간 | 평균 > 500ms면 경고 |
| `lock.acquisition.timeout` | 락 타임아웃 수 | > 0이면 즉시 알림 |
| `ledger.verification.imbalance` | 원장 불일치 수 | > 0이면 CRITICAL |

**Prometheus + Grafana 활용:**

```
Spring Actuator → /actuator/prometheus (메트릭 노출)
     ↓
Prometheus (메트릭 수집 & 저장, 15초 간격 스크래핑)
     ↓
Grafana (시각화 대시보드 + 알림)
```

---

## 12. 데이터베이스 설계

### Q12-1. 인덱스를 왜, 어떻게 설계했나요?

**초보자 설명:**

인덱스 = "책의 목차"

```
인덱스 없이 검색: 책 1페이지부터 끝까지 전부 읽기 (Full Scan)
인덱스 있으면: 목차에서 "Chapter 5: 결제" 찾기 → 바로 해당 페이지로 이동
```

**이 프로젝트의 인덱스:**

```kotlin
// Voucher 엔티티
@Table(indexes = [
    // 인덱스 1: 회원별 상품권 조회 (마이페이지)
    Index(name = "idx_voucher_member", columnList = "memberId, status"),
    // → WHERE memberId = 7 AND status = 'ACTIVE' 쿼리 최적화
    
    // 인덱스 2: 지역별 상태+만료일 조회 (관리자, 스케줄러)
    Index(name = "idx_voucher_region_status", columnList = "regionId, status, expiresAt"),
    // → WHERE regionId = 1 AND status = 'ACTIVE' AND expiresAt < NOW()
    
    // 인덱스 3: 만료 스케줄러 전용
    Index(name = "idx_voucher_expiry", columnList = "status, expiresAt"),
    // → WHERE status IN ('ACTIVE', 'PARTIALLY_USED') AND expiresAt <= NOW()
])
```

**인덱스 설계 원칙:**

```
1. WHERE 조건에 자주 쓰이는 컬럼 → 인덱스 대상
2. 동등 비교(=) 컬럼을 앞에, 범위 비교(<, >) 컬럼을 뒤에
3. 카디널리티(값의 종류)가 높은 컬럼을 앞에

예: idx_voucher_expiry (status, expiresAt)
  - status: 8가지 값 → 동등 비교 WHERE status = 'ACTIVE'
  - expiresAt: 수많은 값 → 범위 비교 WHERE expiresAt <= NOW()
  → status가 앞! (동등 비교로 먼저 범위를 좁힘)
```

---

#### 꼬리질문 12-1-1: 복합 인덱스에서 컬럼 순서가 중요한 이유는?

**초보자 설명 (비유):**

```
전화번호부 정렬: 성(姓) → 이름(名) 순서

"김" 씨 찾기: ✅ (첫 글자로 바로 찾음)
"민수" 찾기: ❌ (전체를 다 봐야 함! 성을 모르니까)
"김민수" 찾기: ✅ (김 → 민수 순서로 좁힘)

인덱스도 같은 원리:
(status, expiresAt) 인덱스에서:
  WHERE status = 'ACTIVE' AND expiresAt < '2024-06-01': ✅ (두 조건 모두 사용)
  WHERE expiresAt < '2024-06-01': ❌ (앞 컬럼 조건이 없어서 인덱스 못 탐!)
  WHERE status = 'ACTIVE': ✅ (앞 컬럼만으로도 사용 가능)
```

---

## 13. Kotlin 특화 질문

### Q13-1. `BigDecimal` 비교 시 `compareTo` vs `==`의 차이는?

**초보자 설명:**

```kotlin
// 사람이 보기에 같은 값
BigDecimal("1.0")   // scale = 1 (소수점 1자리)
BigDecimal("1.00")  // scale = 2 (소수점 2자리)
BigDecimal("1")     // scale = 0

// == (Kotlin의 equals)
BigDecimal("1.0") == BigDecimal("1.00")   // false! 😱
// 이유: scale(소수점 자릿수)이 다르면 다른 객체로 취급

// compareTo
BigDecimal("1.0").compareTo(BigDecimal("1.00")) == 0  // true! ✅
// 이유: 수학적 값만 비교 (1.0 = 1.00 = 1)
```

**이 프로젝트에서:**

```kotlin
// Voucher.kt
fun redeem(amount: BigDecimal) {
    balance -= amount
    // 잔액이 0인지 확인할 때:
    status = if (balance.compareTo(BigDecimal.ZERO) == 0)  // ✅ 올바른 비교
        VoucherStatus.EXHAUSTED
    else
        VoucherStatus.PARTIALLY_USED
}

// 만약 == 을 사용했다면:
// balance = BigDecimal("0.00") (결제 차감 결과)
// BigDecimal("0.00") == BigDecimal.ZERO → false! (ZERO의 scale은 0)
// → 잔액이 0인데 PARTIALLY_USED로 잘못 전이! 버그!
```

---

### Q13-2. `init` 블록의 `require`와 `check`의 차이는?

**초보자 설명:**

```kotlin
class Voucher(val faceValue: BigDecimal, var balance: BigDecimal) {
    init {
        // require: "이 조건을 만족하는 값을 넘겨!" (호출자의 책임)
        require(faceValue > BigDecimal.ZERO) { "액면가는 0보다 커야 합니다" }
        // → IllegalArgumentException (잘못된 인자)
        
        require(balance >= BigDecimal.ZERO) { "잔액은 0 이상이어야 합니다" }
    }
    
    fun redeem(amount: BigDecimal) {
        // check: "지금 이 상태가 올바른지 확인" (객체 상태 검증)
        check(isUsable()) { "사용 불가 상태입니다" }
        // → IllegalStateException (잘못된 상태)
    }
}
```

| | `require` | `check` |
|--|-----------|---------|
| 의미 | "이 값을 줘야 해" | "지금 상태가 이래야 해" |
| 예외 | `IllegalArgumentException` | `IllegalStateException` |
| 용도 | 생성자/메서드 **인자** 검증 | 객체 **상태** 검증 |
| 비유 | "주문서에 빠진 항목 있어요" | "지금 주문 접수 시간이 아닙니다" |

**Fail-Fast 원칙:**
- 잘못된 객체가 생성되는 것 자체를 차단!
- 나중에 어딘가에서 NPE나 비즈니스 오류로 터지는 것보다, **즉시 실패**하는 게 디버깅 쉬움

---

## 14. 금융 도메인 질문

### Q14-1. 정산(Settlement)이란 무엇인가요?

**초보자 설명:**

```
카드 결제를 생각해보세요:

1월 15일: 카페에서 커피 5,000원 카드 결제
  → 카드사가 카페에 바로 5,000원 주나요? ❌
  → "나중에 모아서 줄게" (미수금 발생)

1월 말: 카드사가 카페에 1월 결제 합계를 정산
  → 1월 총 결제 50만원 - 취소 2만원 = 48만원 입금!
  → 이것이 "정산"
```

**이 프로젝트의 정산 흐름:**

```kotlin
// Settlement.kt
class Settlement(
    val merchantId: Long,       // 어느 가맹점
    val periodStart: LocalDate, // 정산 시작일 (예: 2024-01-01)
    val periodEnd: LocalDate,   // 정산 종료일 (예: 2024-01-31)
    var totalAmount: BigDecimal,// 정산 금액
    var status: SettlementStatus, // PENDING → CONFIRMED → PAID
)

// 정산 계산:
// totalAmount = (1월 결제 합계) - (1월 취소 합계)
```

**정산 상태 머신:**

```
PENDING ──── confirm() ───→ CONFIRMED ──── pay() ───→ PAID
    │                                                    
    └──── dispute("이유") ──→ DISPUTED ── confirm() ──→ CONFIRMED
```

**중복 정산 방지:**

```kotlin
@Table(uniqueConstraints = [
    UniqueConstraint(
        name = "uk_settlement_period",
        columnNames = ["merchantId", "periodStart", "periodEnd"]
    )
])
// → 같은 가맹점의 같은 기간 정산이 2번 생성되면 DB에서 에러!
```

---

### Q14-2. 상품권 코드(VoucherCode)의 생성 방식을 설명해주세요.

**모범 답변:**

```kotlin
// VoucherCodeGenerator.kt
fun generate(regionCode: String): String {
    // 1. 15자리 난수 생성 (0-9 + A-Z = 36진법)
    val payload = (1..15).map { chars[random.nextInt(chars.length)] }.joinToString("")
    // 예: "A3K9M2X7P1B4Q8R"
    
    // 2. Luhn mod 36 체크 디짓 계산
    val checkDigit = calculateLuhnMod36CheckDigit(payload)
    // 예: "5"
    
    // 3. 지역코드 + 하이픈 + 페이로드 + 체크디짓
    return "$regionCode-$payload$checkDigit"
    // 예: "SN-A3K9M2X7P1B4Q8R5"
}
```

**각 부분의 역할:**

```
SN - A3K9M2X7P1B4Q8R 5
│     │                 │
│     │                 └─ 체크 디짓 (오타 감지)
│     └─ 15자리 난수 (SecureRandom, 예측 불가)
└─ 지역코드 (서울=SN, 부산=BS 등)
```

**SecureRandom vs Random:**

```kotlin
// ❌ java.util.Random: 시드를 알면 다음 값 예측 가능!
// 공격자가 이전 코드 몇 개를 알면 다음 코드를 추측할 수 있음

// ✅ java.security.SecureRandom: 암호학적으로 안전한 난수
// 예측 불가능 (운영체제의 엔트로피 풀 사용)
```

**Luhn mod 36 체크 디짓:**

```
목적: 사용자가 코드를 잘못 입력했을 때 DB 조회 "전에" 감지

예:
  정확한 코드: SN-A3K9M2X7P1B4Q8R5
  잘못 입력:   SN-A3K9M2X7P1B4Q8R6 (마지막 글자 오타)
  
  체크 디짓 검증: calculateLuhnMod36("A3K9M2X7P1B4Q8R") = '5'
  입력된 디짓: '6'
  '5' ≠ '6' → 즉시 "잘못된 코드입니다" 응답 (DB 조회 안 함!)
  
  효과: DB 부하 감소 + 빠른 응답
```

**코드 유일성 보장:**
- 36^15 ≈ 2.2 × 10^23 가지 조합 (천문학적)
- DB에 UNIQUE 제약 (`@Column(unique = true)`)
- 중복 생성 확률: 10억 개 발행해도 충돌 확률 ≈ 0

---

## 15. 시스템 장애 대응

### Q15-1. 결제 중 서버가 죽으면?

**시나리오별 분석:**

```
시나리오 A: 비즈니스 로직 실행 중 서버 다운
──────────────────────────────────────────
  Lock 획득 ✅ → Transaction Start → voucher.redeem() → [서버 다운!]
  
  결과:
  - DB 트랜잭션: 커밋 안 됨 → 자동 롤백 ✅
  - 상품권 잔액: 변경 안 됨 ✅
  - Redis 분산락: leaseTime(10초) 후 자동 해제 ✅
  - 클라이언트: 타임아웃 → 같은 멱등키로 재요청 → 정상 처리 ✅
```

```
시나리오 B: 커밋 완료 직후, 응답 반환 전 서버 다운
──────────────────────────────────────────
  Lock → Transaction → redeem → COMMIT ✅ → [응답 보내기 전 서버 다운!]
  
  결과:
  - DB: 거래 성공! (커밋됨)
  - 클라이언트: 타임아웃 (응답 못 받음)
  - 클라이언트: 같은 멱등키로 재요청
    → Redis에서 멱등키 발견 → 이전 성공 응답 반환 ✅ (이중 처리 방지)
```

```
시나리오 C: Redis 분산락만 획득하고 서버 다운
──────────────────────────────────────────
  Lock 획득 ✅ → [서버 다운!]
  
  결과:
  - Redis 분산락: 10초 후 자동 만료 (leaseTime)
  - 다른 서버/스레드: 10초 후 해당 상품권 결제 가능
  - 10초 동안 해당 상품권 결제 불가 (일시적 서비스 중단)
```

---

### Q15-2. DB 커넥션이 부족하면?

**배경:**

```
HikariCP (DB 커넥션 풀): 기본 10개 커넥션

동시 요청 100개:
  10개는 즉시 DB 접근 가능
  90개는 대기... (커넥션 풀 고갈!)
  대기 시간 > 30초 → 타임아웃 → 500 에러!
```

**이 시스템에서 특히 위험한 이유:**

```
분산락 대기 중에도 커넥션을 점유할 수 있음!

Thread A: 커넥션 획득 → 분산락 대기 (5초)... → 실제 작업 (0.2초)
→ 5.2초간 커넥션 점유!
```

하지만 이 프로젝트는 `TransactionTemplate`을 분산락 **내부**에서 사용하므로:

```
Thread A: 분산락 대기 (5초) → 분산락 획득 → 커넥션 획득 → 작업 (0.2초) → 커밋 → 반환
→ 커넥션 점유 시간: 0.2초만!
```

**대응 방안:**
1. 커넥션 풀 크기 조정: `maximumPoolSize = (CPU 코어 × 2) + SSD 수`
2. 커넥션 획득 타임아웃 설정
3. 읽기 전용 쿼리에 Read Replica 활용

---

## 16. MSA 전환 & 확장성

### Q16-1. 이 시스템을 MSA로 분리한다면?

**모범 답변:**

```
현재 (모놀리식):
┌──────────────────────────────────────────┐
│  하나의 애플리케이션 (하나의 DB)           │
│                                          │
│  Region + Member + Merchant + Voucher    │
│  + Transaction + Ledger + Settlement     │
└──────────────────────────────────────────┘

MSA 전환:
┌──────────┐  ┌──────────┐  ┌────────────┐  ┌────────────┐
│  Member  │  │  Voucher │  │   Ledger   │  │ Settlement │
│ Service  │  │ Service  │  │  Service   │  │  Service   │
│  (DB1)   │  │  (DB2)   │  │   (DB3)    │  │   (DB4)    │
└────┬─────┘  └────┬─────┘  └─────┬──────┘  └─────┬──────┘
     │              │              │               │
     └──────────────┴──────────────┴───────────────┘
                         │
                    Kafka (이벤트 버스)
```

**분리 기준: 데이터 소유권 (Database per Service)**

| 서비스 | 데이터 | 핵심 기능 |
|--------|--------|----------|
| Member Service | members, auth | 인증, 회원 관리 |
| Voucher Service | vouchers, transactions | 발행, 결제, 환불, 철회 |
| Ledger Service | ledger_entries | 원장 기록, 정합성 검증 |
| Settlement Service | settlements, merchants | 정산 계산, 가맹점 관리 |

**변경 사항:**

| 현재 | MSA |
|------|-----|
| `transactionTemplate.execute {}` (같은 DB TX) | **Saga Pattern** (분산 트랜잭션) |
| `ledgerService.record()` (동기 호출) | **Kafka 이벤트** (비동기) + Outbox |
| `ApplicationEventPublisher` (프로세스 내) | **Kafka** (서비스 간) |
| `voucherRepository.findByIdForUpdate()` (DB Lock) | **서비스별 독립 잠금** |
| `BusinessException` (동기 에러) | **gRPC/HTTP** + Circuit Breaker |

---

### Q16-2. 트래픽이 10배 증가하면 어떤 부분이 병목이 되나요?

**분석:**

```
현재: 초당 100건 처리 가능
10배: 초당 1,000건 필요

병목 순서:
1. DB 커넥션 풀 (10개 → 부족!)
2. Redis 분산락 (Hot Key 집중)
3. LedgerEntry INSERT (쓰기 부하)
4. 단일 DB 서버의 쓰기 처리 한계
```

**대응 전략:**

```
[단기 - 수직 확장]
- DB 서버 스펙 업그레이드 (CPU, RAM, SSD)
- 커넥션 풀 크기 조정 (10 → 30)
- Redis 서버 스펙 업그레이드

[중기 - 읽기/쓰기 분리]
- MySQL Read Replica 추가
- 조회 쿼리 → Read Replica로 라우팅
- 결제/발행 → Primary로 라우팅

[장기 - 수평 확장]
- DB 샤딩: regionId 기준 (서울 DB, 부산 DB, ...)
- 서비스 분리: Voucher Service 인스턴스 3대
- 캐싱: 상품권 조회에 Redis Cache 적용
- 비동기 원장: Kafka → 별도 Ledger DB (최종 일관성)
```

---

## 17. 코드 품질 & 설계 의사결정

### Q17-1. 엔티티에서 연관관계 대신 ID 참조를 사용한 이유는?

**비교:**

```kotlin
// 방법 1: JPA 연관관계 (객체 참조)
class Voucher(
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "member_id")
    val member: Member,  // Member 객체를 직접 들고 있음
)
// 사용: voucher.member.name → 자동 JOIN 또는 Lazy Loading

// 방법 2: ID 참조 (이 프로젝트)
class Voucher(
    val memberId: Long,  // Member의 ID만 들고 있음
)
// 사용: val member = memberService.getById(voucher.memberId) → 별도 조회
```

**ID 참조를 선택한 이유:**

| 장점 | 설명 |
|------|------|
| Bounded Context 분리 | Voucher 도메인이 Member 도메인에 직접 의존하지 않음 |
| MSA 전환 용이 | ID 참조 → API 호출로 자연스럽게 전환 |
| Lazy Loading 문제 없음 | OSIV=false에서 LazyInitializationException 원천 차단 |
| 테스트 용이 | Member 없이 Voucher 단독 테스트 가능 |
| N+1 방지 | 불필요한 JOIN이 발생하지 않음 |

**예외: Merchant는 연관관계 사용**

```kotlin
// Merchant.kt - ManyToOne 사용
@ManyToOne(fetch = FetchType.LAZY)
@JoinColumn(name = "region_id")
val region: Region,

@ManyToOne(fetch = FetchType.LAZY)
@JoinColumn(name = "owner_id")
val owner: Member,
```

이유: 가맹점 승인/정산 시 Region 정보가 자주 필요하고, 같은 Bounded Context 내에서의 밀접한 관계이므로 연관관계를 유지.

---

### Q17-2. 서비스를 왜 5개로 분리했나요? 하나의 VoucherService로 합쳐도 되지 않나요?

**모범 답변:**

```
현재: 5개 서비스
- VoucherIssueService (발행)
- VoucherRedemptionService (결제)
- VoucherRefundService (환불)
- VoucherWithdrawalService (청약철회)
- VoucherExpiryScheduler (만료 배치)

합치면?
- VoucherService (700줄 이상의 거대 클래스!)
```

**분리 이유:**

1. **단일 책임 원칙(SRP)**: 각 서비스가 하나의 유스케이스만 담당
2. **의존성 최소화**: 발행은 Redis Lua가 필요하지만, 환불은 불필요
3. **테스트 용이**: 결제 서비스만 테스트할 때 다른 유스케이스 모킹 불필요
4. **동시성 패턴 차이**: 발행은 member lock, 결제는 voucher lock
5. **배포 유연성**: MSA 전환 시 각각 독립 서비스로 분리 가능

---

## 18. 실무/상황 질문

### Q18-1. 정산 금액이 틀린 것을 발견했다면 어떻게 대응하나요?

**STAR 프레임워크로 답변:**

**Situation**: 가맹점주가 "이번 달 정산 금액이 예상보다 10만원 적다"고 문의

**Task**: 원인 파악 + 올바른 금액으로 보정 + 재발 방지

**Action**:
1. **즉시 조치**: 해당 정산 건 `dispute("가맹점 이의제기: 금액 불일치")` → DISPUTED로 전환 (지급 차단)

2. **원인 분석**:
   ```sql
   -- 해당 기간의 모든 거래 조회
   SELECT * FROM transactions 
   WHERE merchantId = ? AND createdAt BETWEEN ? AND ?
   ORDER BY createdAt;
   
   -- 원장에서 실제 금액 확인
   SELECT SUM(amount) FROM ledger_entries 
   WHERE account = 'MERCHANT_RECEIVABLE' AND side = 'DEBIT'
   AND transactionId IN (...);
   ```

3. **원인별 대응**:
   - 취소 처리가 누락된 거래가 있었다면 → 보상 분개로 보정
   - 코드 버그(정산 계산 로직 오류)라면 → 코드 수정 + 보정 분개
   - 정상이라면 → 가맹점에 상세 내역 제공

4. **수정 방법**: 원장은 절대 수정하지 않고, 보정 분개(Adjusting Entry)를 추가

**Result**: 정확한 금액으로 재정산 + 재발 방지 코드 수정 + 검증 로직 강화

---

### Q18-2. 이 프로젝트에서 가장 어려웠던 기술적 도전은?

**예시 답변 (STAR):**

**Situation**: 
10개 스레드 동시 결제 테스트에서 간헐적으로 잔액이 음수(-10,000원)가 되는 버그 발견

**Task**: 
동시성 버그의 원인을 찾아 수정하고, 재발하지 않음을 증명

**Action**:

처음에는 `@Transactional` + 분산락 조합을 사용했습니다:
```kotlin
// 초기 코드 (버그)
@Transactional  // 메서드 시작 시 TX 열림
fun redeem(voucherId: Long, amount: BigDecimal) {
    lockManager.withVoucherLock(voucherId) {
        val voucher = voucherRepository.findByIdForUpdate(voucherId)!!
        voucher.redeem(amount)
    }  // 여기서 락 해제 → 다른 스레드 진입 가능
}      // 여기서 TX 커밋 → 아직 커밋 안 된 데이터를 다른 스레드가 읽음!
```

디버깅 과정:
1. 로그 추가: 락 획득/해제 시점, 커밋 시점 기록
2. 발견: "락 해제" → "다른 스레드 잔액 읽기" → "첫 스레드 커밋" 순서 확인
3. 원인: Lock-Commit 순서 역전 (커밋 전에 락이 풀림)

수정:
```kotlin
// 수정된 코드
fun redeem(voucherId: Long, amount: BigDecimal) {
    lockManager.withVoucherLock(voucherId) {
        transactionTemplate.execute {  // 락 내부에서 TX 시작
            val voucher = voucherRepository.findByIdForUpdate(voucherId)!!
            voucher.redeem(amount)
        }  // 여기서 TX 커밋!
    }      // 여기서 락 해제! (커밋 후!)
}
```

**Result**: 
- 동시성 테스트 100회 반복 통과 (잔액 음수 0건)
- 교훈: "동시성 버그는 코드 리뷰만으로 발견 못 함. 동시성 테스트 필수!"

---

### Q18-3. 이 프로젝트의 한계와 개선점은?

**모범 답변 (자기 객관화 능력 보여주기):**

| 영역 | 현재 한계 | 개선 방향 |
|------|----------|----------|
| 할인 구매 | `purchaseAmount` 미관리 | 실결제액 필드 추가 필요 |
| 보안 | `anyRequest().permitAll()` | JWT 필터 + 역할별 접근 제어 |
| 이벤트 재처리 | FailedEvent 재시도 스케줄러 미구현 | Scheduled retry + dead letter 처리 |
| 모니터링 | 메트릭만 수집, 알림 미설정 | Grafana 대시보드 + PagerDuty 연동 |
| 성능 | Read Replica 미적용 | CQRS 패턴 + 읽기 전용 DB |
| 배포 | 무중단 배포 미구현 | Blue-Green 또는 Rolling Update |

"이러한 한계를 인식하고 있으며, 우선순위에 따라 점진적으로 개선할 계획입니다."

---

## 19. 면접 팁 & STAR 프레임워크

### STAR 프레임워크

모든 경험/상황 질문에 이 구조로 답변하세요:

```
S (Situation): 상황 설명 (1-2문장)
T (Task): 내가 해결해야 했던 것 (1문장)
A (Action): 구체적으로 한 일 (가장 길게! 기술적 디테일 포함)
R (Result): 결과 + 배운 점 (정량적 수치 포함하면 좋음)
```

### 답변 시 주의사항

1. **트레이드오프를 항상 언급**
   ```
   ❌ "A를 사용했습니다"
   ✅ "A와 B를 비교했을 때, X 이유로 A를 선택했습니다. B의 장점인 Y는 포기했지만,
       이 상황에서는 A의 Z가 더 중요했습니다"
   ```

2. **"왜?"에 3단계 대비**
   ```
   1단계: "분산락을 사용했습니다" → 왜?
   2단계: "동시성 문제를 해결하기 위해서입니다" → 왜 분산락인가?
   3단계: "여러 서버 인스턴스에서 동작해야 하므로 DB 락만으로는 부족합니다"
   ```

3. **숫자를 사용하라**
   ```
   ❌ "성능이 좋아졌습니다"
   ✅ "동시성 테스트에서 10스레드 × 100회 반복 결과, 잔액 음수 발생률 0%"
   ```

4. **확장 가능성 언급**
   ```
   "현재는 이렇게 구현했지만, 트래픽이 증가하면 X → Y로 전환할 수 있습니다"
   → 현재 기술 선택에 대한 확신 + 미래 대응력 어필
   ```

5. **실패 경험을 당당하게**
   ```
   "처음에 @Transactional로 했을 때 동시성 버그가 발생했습니다.
    원인을 분석한 결과 Lock-Commit 순서 문제임을 발견했고,
    TransactionTemplate으로 해결했습니다.
    이 경험으로 '동시성 문제는 반드시 테스트로 검증해야 한다'는 원칙을 세웠습니다."
   ```

---

### 자주 하는 실수

| 실수 | 올바른 접근 |
|------|------------|
| "이론만 설명" | 이 프로젝트의 **실제 코드**를 예시로 |
| "장점만 나열" | 트레이드오프(단점/포기한 것) 함께 언급 |
| "완벽하다고 주장" | 한계를 인정하고 개선 방향 제시 |
| "용어만 나열" | 왜 그 기술을 선택했는지 **이유** 설명 |
| "질문의 의도 파악 실패" | 면접관이 듣고 싶은 것: "이 사람은 실제로 이해하고 있는가?" |
