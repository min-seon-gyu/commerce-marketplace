# Plan 3 — POINT EARN (적립 전용 + 전역 정합성)

## Goal
회원 포인트 **적립(EARN) 전용** 도메인을 추가한다. 결제(redeem) 트랜잭션과 **동기**로 `record(DEBIT POINT_BALANCE, CREDIT POINT_FUNDING, E, txId, POINT_EARN)` 2-leg 분개를 기록하고, 적립 기준액 = **쿠폰 할인 적용 후 실제 결제액**(plain redeem에서는 결제 금액 그대로), 1원 단위 `HALF_UP` 반올림으로 산정한다. `PointAccount`(캐시 잔액 + `@Version`)와 append-only `PointTransaction`을 도입하고, `GET /api/v1/members/{memberId}/points` 조회 API를 제공한다. 마지막으로 `LedgerVerificationService`에 **전역 불변식 `netBalanceByAccount(POINT_BALANCE) == Σ PointAccount.balance`** 를 추가하여 일일 검증에 못 박는다.

**범위 (MUST = 적립 전용)**: 적립 + 전역 정합성 + 조회 API.
**STRETCH (이 계획 밖)**: 포인트 결제수단(tender, point-on-point), 회원별(per-member) 정합성 지역화, 포인트 만료(breakage) 잡, 분산 point 락, 적립 포인트 취소 회수.

## Architecture
- 신규 패키지 `com.commerce.point` (기존 컨벤션: `domain` / `infrastructure` / `application` / `interfaces` / `interfaces/dto`).
- 적립은 **별도 after-commit 리스너 금지**. `VoucherRedemptionService.redeem`의 `transactionTemplate.execute { ... }` 블록 안에서 `PointEarnService.earn(...)`를 동기 호출한다 (leg 유실 방지). POINT_EARN 분개는 redemption 분개와 **같은 `transactionId`(redemption tx)** 를 공유한다 — 쿠폰 보조분(Plan 2)이 같은 txId에 2-leg를 추가하는 패턴과 동일.
- `POINT_BALANCE`는 **차변정상**(net = 차변−대변)으로 `VOUCHER_BALANCE`와 동일 취급 → 검증 공식 재사용. 캐시(`PointAccount.balance`)와 원장 net이 항상 일치해야 한다.

## Tech Stack
- Gradle Kotlin DSL, Spring Boot 3.2.5, Kotlin 1.9.23, Java 17.
- 단위: Kotest 5.8.1 `DescribeSpec` + MockK 1.13.10.
- 통합/정합성: `com.commerce.support.IntegrationTestSupport` (`@SpringBootTest @ActiveProfiles("test") @Testcontainers`, MySQL+Redis) + `TestFixtures`. **Docker 데몬 필요**.
- 단일 테스트 실행: `./gradlew test --tests "com.commerce.<FqcnTest>"`.

## Global Constraints
- **Plan 1 의존**: `AccountCode.POINT_BALANCE("포인트 잔액")`, `AccountCode.POINT_FUNDING("포인트 출연금")`, `LedgerEntryType.POINT_EARN` 가 **이미 추가되어 있어야** 한다. (Task 1 Step 1에서 선검증.)
- 락 순서 정준 규칙: 정준 순서는 `coupon → voucher (→ point STRETCH)`. 본 계획은 **분산 point 락을 추가하지 않는다**. 동일 회원 포인트 계정 동시 갱신은 **이미 열린 redeem 트랜잭션 내부의 DB 비관적 락**(`findByMemberIdForUpdate`, SELECT FOR UPDATE)으로 직렬화한다. 신규 회원의 최초 적립 동시 생성 경합은 `memberId` UNIQUE 제약으로 차단(드물게 한쪽 redeem 롤백) — 분산 point 락은 STRETCH.
- 적립 기준액(`baseAmount`)은 호출자가 전달한 **실제 결제액**이다. plain redeem에서는 `redeem(...)`의 `amount`(Task 3). Plan 2(쿠폰)가 있으면 오케스트레이터가 `T−D`(post-coupon, 할인분 `D` 제외)를 전달한다 — 쿠폰 경로 통합·검증은 **Task 6**(Plan 2 의존)에서 수행한다.
- 적립률 `point.earn-rate` = **0.01(1%)**, 반올림 `RoundingMode.HALF_UP`, scale 0(1원 단위). `application.yml`에 설정, `@Value`로 주입.
- 적립액이 0(기준액이 너무 작음)이면 적립을 **건너뛴다**(`LedgerEntry`/`PointTransaction`은 amount>0 필수).
- 신규 컨트롤러는 호출자 신원을 **인증 principal**(Plan 1 `JwtAuthenticationFilter`가 `principal = memberId: Long` 설정)에서 도출한다. 요청 body의 memberId를 신뢰하지 않는다.
- `record()`는 활성 트랜잭션 내부에서만 호출 가능(2-leg 균형). POINT_EARN 호출은 redeem 트랜잭션 내부에서 이뤄지므로 충족.

---

### Task 1: Point 도메인 엔티티 + 리포지토리

**Files:**
- Create `src/main/kotlin/com/komsco/voucher/point/domain/PointTransactionType.kt`
- Create `src/main/kotlin/com/komsco/voucher/point/domain/PointAccount.kt`
- Create `src/main/kotlin/com/komsco/voucher/point/domain/PointTransaction.kt`
- Create `src/main/kotlin/com/komsco/voucher/point/infrastructure/PointAccountJpaRepository.kt`
- Create `src/main/kotlin/com/komsco/voucher/point/infrastructure/PointTransactionJpaRepository.kt`
- Create `src/test/kotlin/com/komsco/voucher/point/domain/PointAccountTest.kt`

**Interfaces:**
- Consumes: `com.commerce.common.domain.BaseEntity` (`id`, `@Version version`), `AccountCode.POINT_BALANCE/POINT_FUNDING` (Plan 1, 검증용).
- Produces:
  - `enum class PointTransactionType { EARN }`
  - `class PointAccount(memberId: Long, balance: BigDecimal = ZERO) : BaseEntity` — `fun earn(amount: BigDecimal)`, `var balance`.
  - `class PointTransaction(memberId, type, amount, balanceAfter, sourceTransactionId, createdAt)` — `@Immutable`, append-only, `val id`.
  - `interface PointAccountJpaRepository` — `findByMemberId(Long): PointAccount?`, `findByMemberIdForUpdate(Long): PointAccount?`, `sumAllBalances(): BigDecimal`, `overwriteBalance(memberId, balance)`.
  - `interface PointTransactionJpaRepository` — `findByMemberIdOrderByCreatedAtDesc(Long)`, `findBySourceTransactionId(Long)`.

- [ ] **Step 1: Plan 1 선결 enum 검증 (실행만, 코드 변경 없음)**
  실행:
  ```bash
  grep -nE "POINT_BALANCE|POINT_FUNDING" src/main/kotlin/com/komsco/voucher/ledger/domain/AccountCode.kt
  grep -n "POINT_EARN" src/main/kotlin/com/komsco/voucher/ledger/domain/LedgerEntryType.kt
  ```
  기대: 세 심볼이 모두 매치됨(각 grep이 결과 1줄 이상). **매치가 없으면 중단하고 Plan 1을 먼저 완료**한다. 매치되면 다음 단계로.

- [ ] **Step 2: (TDD) 실패하는 PointAccount 단위 테스트 작성**
  `src/test/kotlin/com/komsco/voucher/point/domain/PointAccountTest.kt`:
  ```kotlin
  package com.commerce.point.domain

  import io.kotest.assertions.throwables.shouldThrow
  import io.kotest.core.spec.style.DescribeSpec
  import io.kotest.matchers.shouldBe
  import java.math.BigDecimal

  class PointAccountTest : DescribeSpec({

      describe("earn") {
          it("adds the amount to the cached balance") {
              val account = PointAccount(memberId = 1L)
              account.earn(BigDecimal("200"))
              account.balance.compareTo(BigDecimal("200")) shouldBe 0
              account.earn(BigDecimal("150"))
              account.balance.compareTo(BigDecimal("350")) shouldBe 0
          }

          it("rejects non-positive amounts") {
              val account = PointAccount(memberId = 1L)
              shouldThrow<IllegalArgumentException> { account.earn(BigDecimal.ZERO) }
              shouldThrow<IllegalArgumentException> { account.earn(BigDecimal("-1")) }
          }
      }

      describe("construction") {
          it("rejects a negative initial balance") {
              shouldThrow<IllegalArgumentException> {
                  PointAccount(memberId = 1L, balance = BigDecimal("-1"))
              }
          }
      }
  })
  ```

- [ ] **Step 3: 실패 확인**
  실행: `./gradlew test --tests "com.commerce.point.domain.PointAccountTest"`
  기대: **FAIL** (컴파일 에러 — `PointAccount` 미존재).

- [ ] **Step 4: `PointTransactionType` 작성**
  `src/main/kotlin/com/komsco/voucher/point/domain/PointTransactionType.kt`:
  ```kotlin
  package com.commerce.point.domain

  // MUST 범위는 적립 전용. STRETCH(별도 계획): SPEND, EXPIRE, CANCEL.
  enum class PointTransactionType {
      EARN
  }
  ```

- [ ] **Step 5: `PointAccount` 작성**
  `src/main/kotlin/com/komsco/voucher/point/domain/PointAccount.kt`:
  ```kotlin
  package com.commerce.point.domain

  import com.commerce.common.domain.BaseEntity
  import jakarta.persistence.Column
  import jakarta.persistence.Entity
  import jakarta.persistence.Table
  import java.math.BigDecimal

  @Entity
  @Table(name = "point_accounts")
  class PointAccount(
      @Column(nullable = false, unique = true)
      val memberId: Long,

      @Column(nullable = false)
      var balance: BigDecimal = BigDecimal.ZERO,
  ) : BaseEntity() {

      init {
          require(balance >= BigDecimal.ZERO) { "포인트 잔액은 0 이상이어야 합니다" }
      }

      /** 적립: 캐시 잔액 증가. 원장 분개는 PointEarnService가 동기로 기록한다. */
      fun earn(amount: BigDecimal) {
          require(amount > BigDecimal.ZERO) { "적립액은 0보다 커야 합니다" }
          balance += amount
      }
  }
  ```

- [ ] **Step 6: `PointTransaction` 작성 (append-only)**
  `src/main/kotlin/com/komsco/voucher/point/domain/PointTransaction.kt`:
  ```kotlin
  package com.commerce.point.domain

  import jakarta.persistence.Column
  import jakarta.persistence.Entity
  import jakarta.persistence.EnumType
  import jakarta.persistence.Enumerated
  import jakarta.persistence.GeneratedValue
  import jakarta.persistence.GenerationType
  import jakarta.persistence.Id
  import jakarta.persistence.Index
  import jakarta.persistence.Table
  import org.hibernate.annotations.Immutable
  import java.math.BigDecimal
  import java.time.LocalDateTime

  @Entity
  @Immutable
  @Table(
      name = "point_transactions",
      indexes = [
          Index(name = "idx_point_tx_member", columnList = "memberId, createdAt"),
          Index(name = "idx_point_tx_source", columnList = "sourceTransactionId"),
      ]
  )
  class PointTransaction(
      @Column(nullable = false)
      val memberId: Long,

      @Enumerated(EnumType.STRING)
      @Column(nullable = false, length = 20)
      val type: PointTransactionType,

      @Column(nullable = false)
      val amount: BigDecimal,

      @Column(nullable = false)
      val balanceAfter: BigDecimal,

      // 적립을 유발한 원 거래(redemption Transaction)의 id.
      @Column(nullable = false)
      val sourceTransactionId: Long,

      @Column(nullable = false)
      val createdAt: LocalDateTime = LocalDateTime.now(),
  ) {
      @Id
      @GeneratedValue(strategy = GenerationType.IDENTITY)
      val id: Long = 0L

      init {
          require(amount > BigDecimal.ZERO) { "포인트 거래 금액은 0보다 커야 합니다" }
      }
  }
  ```

- [ ] **Step 7: `PointAccountJpaRepository` 작성**
  `src/main/kotlin/com/komsco/voucher/point/infrastructure/PointAccountJpaRepository.kt`:
  ```kotlin
  package com.commerce.point.infrastructure

  import com.commerce.point.domain.PointAccount
  import jakarta.persistence.LockModeType
  import org.springframework.data.jpa.repository.JpaRepository
  import org.springframework.data.jpa.repository.Lock
  import org.springframework.data.jpa.repository.Modifying
  import org.springframework.data.jpa.repository.Query
  import org.springframework.data.repository.query.Param
  import java.math.BigDecimal

  interface PointAccountJpaRepository : JpaRepository<PointAccount, Long> {

      fun findByMemberId(memberId: Long): PointAccount?

      @Lock(LockModeType.PESSIMISTIC_WRITE)
      @Query("SELECT p FROM PointAccount p WHERE p.memberId = :memberId")
      fun findByMemberIdForUpdate(@Param("memberId") memberId: Long): PointAccount?

      @Query("SELECT COALESCE(SUM(p.balance), 0) FROM PointAccount p")
      fun sumAllBalances(): BigDecimal

      // 정합성 회귀 테스트/운영 보정용 — 캐시 잔액을 직접 덮어쓴다(@Transactional 내에서만 호출).
      @Modifying
      @Query("UPDATE PointAccount p SET p.balance = :balance WHERE p.memberId = :memberId")
      fun overwriteBalance(@Param("memberId") memberId: Long, @Param("balance") balance: BigDecimal)
  }
  ```

- [ ] **Step 8: `PointTransactionJpaRepository` 작성**
  `src/main/kotlin/com/komsco/voucher/point/infrastructure/PointTransactionJpaRepository.kt`:
  ```kotlin
  package com.commerce.point.infrastructure

  import com.commerce.point.domain.PointTransaction
  import org.springframework.data.jpa.repository.JpaRepository

  interface PointTransactionJpaRepository : JpaRepository<PointTransaction, Long> {

      fun findByMemberIdOrderByCreatedAtDesc(memberId: Long): List<PointTransaction>

      fun findBySourceTransactionId(sourceTransactionId: Long): List<PointTransaction>
  }
  ```

- [ ] **Step 9: 단위 테스트 통과 확인**
  실행: `./gradlew test --tests "com.commerce.point.domain.PointAccountTest"`
  기대: **PASS** (3개 케이스 통과).

- [ ] **Step 10: 커밋**
  실행:
  ```bash
  git add -A && git commit -m "$(cat <<'EOF'
feat(point): PointAccount/PointTransaction 도메인 + 리포지토리 추가

- POINT_BALANCE 차변정상 캐시(@Version) + append-only EARN 거래
- 적립 전용(STRETCH: SPEND/EXPIRE/CANCEL)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
EOF
)"
  ```

---

### Task 2: PointEarnService (적립 계산 + 반올림 + 동기 분개)

**Files:**
- Modify `src/main/resources/application.yml` (root에 `point.earn-rate` 추가)
- Create `src/main/kotlin/com/komsco/voucher/point/application/PointEarnService.kt`
- Create `src/test/kotlin/com/komsco/voucher/point/application/PointEarnServiceTest.kt`

**Interfaces:**
- Consumes: `LedgerService.record(debitAccount, creditAccount, amount, transactionId, entryType)`, `AccountCode.POINT_BALANCE/POINT_FUNDING`, `LedgerEntryType.POINT_EARN`, `PointAccountJpaRepository`, `PointTransactionJpaRepository`, `io.micrometer.core.instrument.MeterRegistry`.
- Produces: `class PointEarnService` —
  - `fun calculateEarn(baseAmount: BigDecimal): BigDecimal` (pure: `baseAmount * earnRate`, scale 0 HALF_UP)
  - `fun earn(memberId: Long, baseAmount: BigDecimal, sourceTransactionId: Long): BigDecimal` (활성 tx 내부 동기 호출; 적립액 반환, 0이면 미적립)

- [ ] **Step 1: (TDD) 실패하는 적립 계산/반올림 단위 테스트 작성**
  `src/test/kotlin/com/komsco/voucher/point/application/PointEarnServiceTest.kt`:
  ```kotlin
  package com.commerce.point.application

  import io.kotest.core.spec.style.DescribeSpec
  import io.kotest.matchers.shouldBe
  import io.micrometer.core.instrument.simple.SimpleMeterRegistry
  import io.mockk.mockk
  import java.math.BigDecimal

  class PointEarnServiceTest : DescribeSpec({

      // 순수 계산 검증이라 리포지토리/원장은 모킹, 적립률은 0.01 고정 주입.
      val service = PointEarnService(
          pointAccountRepository = mockk(relaxed = true),
          pointTransactionRepository = mockk(relaxed = true),
          ledgerService = mockk(relaxed = true),
          meterRegistry = SimpleMeterRegistry(),
          earnRate = BigDecimal("0.01"),
      )

      describe("calculateEarn (1% , 1원 단위 HALF_UP)") {
          it("rounds exact and fractional amounts") {
              service.calculateEarn(BigDecimal("20000")).compareTo(BigDecimal("200")) shouldBe 0
              service.calculateEarn(BigDecimal("15000")).compareTo(BigDecimal("150")) shouldBe 0
              // 12350 * 0.01 = 123.50 -> HALF_UP -> 124
              service.calculateEarn(BigDecimal("12350")).compareTo(BigDecimal("124")) shouldBe 0
              // 12340 * 0.01 = 123.40 -> HALF_UP -> 123
              service.calculateEarn(BigDecimal("12340")).compareTo(BigDecimal("123")) shouldBe 0
          }

          it("returns 0 when the base amount is too small to earn 1 won") {
              // 49 * 0.01 = 0.49 -> 0
              service.calculateEarn(BigDecimal("49")).compareTo(BigDecimal.ZERO) shouldBe 0
          }
      }
  })
  ```

- [ ] **Step 2: 실패 확인**
  실행: `./gradlew test --tests "com.commerce.point.application.PointEarnServiceTest"`
  기대: **FAIL** (컴파일 에러 — `PointEarnService` 미존재).

- [ ] **Step 3: `application.yml`에 적립률 추가**
  `src/main/resources/application.yml`의 `server:` 블록 바로 위(또는 아무 root 위치)에 다음 root 키를 추가:
  ```yaml
  point:
    earn-rate: "0.01"
  ```
  (정확한 삽입: 기존 `redis:` 매핑 종료 직후, `server:` 라인 앞에 위 3줄을 root 들여쓰기로 추가. `application-test.yml`은 base를 상속하므로 테스트도 0.01을 사용.)

- [ ] **Step 4: `PointEarnService` 작성**
  `src/main/kotlin/com/komsco/voucher/point/application/PointEarnService.kt`:
  ```kotlin
  package com.commerce.point.application

  import com.commerce.ledger.application.LedgerService
  import com.commerce.ledger.domain.AccountCode
  import com.commerce.ledger.domain.LedgerEntryType
  import com.commerce.point.domain.PointAccount
  import com.commerce.point.domain.PointTransaction
  import com.commerce.point.domain.PointTransactionType
  import com.commerce.point.infrastructure.PointAccountJpaRepository
  import com.commerce.point.infrastructure.PointTransactionJpaRepository
  import io.micrometer.core.instrument.MeterRegistry
  import org.springframework.beans.factory.annotation.Value
  import org.springframework.stereotype.Service
  import java.math.BigDecimal
  import java.math.RoundingMode

  @Service
  class PointEarnService(
      private val pointAccountRepository: PointAccountJpaRepository,
      private val pointTransactionRepository: PointTransactionJpaRepository,
      private val ledgerService: LedgerService,
      private val meterRegistry: MeterRegistry,
      @Value("\${point.earn-rate}") private val earnRate: BigDecimal,
  ) {

      /** 적립액 = baseAmount * earnRate, 1원 단위 HALF_UP. */
      fun calculateEarn(baseAmount: BigDecimal): BigDecimal =
          baseAmount.multiply(earnRate).setScale(0, RoundingMode.HALF_UP)

      /**
       * 결제 트랜잭션과 **동기**로 적립을 기록한다. 반드시 활성 트랜잭션 내부에서 호출한다.
       * baseAmount = 쿠폰 할인 적용 후 실제 결제액(plain redeem에서는 결제 금액 그대로).
       * 적립액이 0이면 아무 것도 기록하지 않고 0을 반환한다.
       * 동일 회원 동시 적립은 findByMemberIdForUpdate(SELECT FOR UPDATE)로 직렬화한다.
       */
      fun earn(memberId: Long, baseAmount: BigDecimal, sourceTransactionId: Long): BigDecimal {
          val earnAmount = calculateEarn(baseAmount)
          if (earnAmount.signum() <= 0) return BigDecimal.ZERO

          val account = pointAccountRepository.findByMemberIdForUpdate(memberId)
              ?: pointAccountRepository.save(PointAccount(memberId = memberId))
          account.earn(earnAmount)

          pointTransactionRepository.save(
              PointTransaction(
                  memberId = memberId,
                  type = PointTransactionType.EARN,
                  amount = earnAmount,
                  balanceAfter = account.balance,
                  sourceTransactionId = sourceTransactionId,
              )
          )

          // POINT_BALANCE 차변정상: DEBIT POINT_BALANCE / CREDIT POINT_FUNDING (redemption tx와 동일 txId)
          ledgerService.record(
              debitAccount = AccountCode.POINT_BALANCE,
              creditAccount = AccountCode.POINT_FUNDING,
              amount = earnAmount,
              transactionId = sourceTransactionId,
              entryType = LedgerEntryType.POINT_EARN,
          )

          meterRegistry.counter("point.earn.count").increment()
          return earnAmount
      }
  }
  ```

- [ ] **Step 5: 단위 테스트 통과 확인**
  실행: `./gradlew test --tests "com.commerce.point.application.PointEarnServiceTest"`
  기대: **PASS** (반올림/0 케이스 통과).

- [ ] **Step 6: 커밋**
  실행:
  ```bash
  git add -A && git commit -m "$(cat <<'EOF'
feat(point): PointEarnService 적립 산정/동기 분개 추가

- 적립률 point.earn-rate(0.01), 1원 단위 HALF_UP
- DEBIT POINT_BALANCE / CREDIT POINT_FUNDING (POINT_EARN), redemption txId 공유
- 적립액 0이면 미적립(LedgerEntry amount>0 보장)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
EOF
)"
  ```

---

### Task 3: 결제(redeem)에 적립 동기 통합 + 통합 테스트 + 기존 E2E 카운트 보정

**Files:**
- Modify `src/main/kotlin/com/komsco/voucher/voucher/application/VoucherRedemptionService.kt` (생성자 22-30, redeem 본문 ~58-71)
- Modify `src/test/kotlin/com/komsco/voucher/integration/E2EFlowTest.kt` (lines 90-96: 카운트 8 → 12)
- Create `src/test/kotlin/com/komsco/voucher/integration/PointEarnIntegrationTest.kt`

**Interfaces:**
- Consumes: `PointEarnService.earn(memberId, baseAmount, sourceTransactionId)`, `Voucher.memberId`, `RedemptionResult.transactionId`.
- Produces: redeem 트랜잭션 내부에서 POINT_EARN 분개 + PointAccount/PointTransaction 갱신(동기).

- [ ] **Step 1: (TDD) 실패하는 적립 통합 테스트 작성**
  `src/test/kotlin/com/komsco/voucher/integration/PointEarnIntegrationTest.kt`:
  ```kotlin
  package com.commerce.integration

  import com.commerce.ledger.application.LedgerVerificationService
  import com.commerce.ledger.domain.AccountCode
  import com.commerce.ledger.domain.LedgerEntrySide
  import com.commerce.ledger.infrastructure.LedgerJpaRepository
  import com.commerce.point.domain.PointTransactionType
  import com.commerce.point.infrastructure.PointAccountJpaRepository
  import com.commerce.point.infrastructure.PointTransactionJpaRepository
  import com.commerce.support.IntegrationTestSupport
  import com.commerce.support.TestFixtures
  import com.commerce.voucher.application.VoucherRedemptionService
  import io.kotest.matchers.nulls.shouldNotBeNull
  import io.kotest.matchers.shouldBe
  import org.junit.jupiter.api.BeforeEach
  import org.junit.jupiter.api.Test
  import org.springframework.beans.factory.annotation.Autowired
  import java.math.BigDecimal
  import java.util.UUID

  class PointEarnIntegrationTest : IntegrationTestSupport() {

      @Autowired lateinit var fixtures: TestFixtures
      @Autowired lateinit var redemptionService: VoucherRedemptionService
      @Autowired lateinit var pointAccountRepository: PointAccountJpaRepository
      @Autowired lateinit var pointTransactionRepository: PointTransactionJpaRepository
      @Autowired lateinit var ledgerRepository: LedgerJpaRepository
      @Autowired lateinit var verificationService: LedgerVerificationService

      private var regionId: Long = 0
      private var memberId: Long = 0
      private var merchantId: Long = 0

      @BeforeEach
      fun setup() {
          val region = fixtures.createRegion(code = UUID.randomUUID().toString().take(2).uppercase())
          val member = fixtures.createMember()
          val owner = fixtures.createMember()
          val merchant = fixtures.createMerchant(region, owner)
          regionId = region.id
          memberId = member.id
          merchantId = merchant.id
      }

      @Test
      fun `plain redeem earns 1 percent points and posts a balanced POINT_EARN ledger pair`() {
          val voucher = fixtures.issueVoucher(memberId, regionId, BigDecimal("50000"))

          // 20,000원 결제 → 1% 적립 = 200원
          val result = redemptionService.redeem(voucher.id, merchantId, BigDecimal("20000"))

          // 1) 캐시 잔액
          val account = pointAccountRepository.findByMemberId(memberId)
          account.shouldNotBeNull()
          account.balance.compareTo(BigDecimal("200")) shouldBe 0

          // 2) append-only EARN 거래 (원 거래 link)
          val pointTxs = pointTransactionRepository.findBySourceTransactionId(result.transactionId)
          pointTxs.size shouldBe 1
          pointTxs[0].type shouldBe PointTransactionType.EARN
          pointTxs[0].amount.compareTo(BigDecimal("200")) shouldBe 0

          // 3) 같은 txId에 POINT_EARN 2-leg (DEBIT POINT_BALANCE / CREDIT POINT_FUNDING)
          val pointEntries = ledgerRepository.findByTransactionId(result.transactionId)
              .filter { it.account == AccountCode.POINT_BALANCE || it.account == AccountCode.POINT_FUNDING }
          pointEntries.size shouldBe 2
          val debit = pointEntries.first { it.side == LedgerEntrySide.DEBIT }
          val credit = pointEntries.first { it.side == LedgerEntrySide.CREDIT }
          debit.account shouldBe AccountCode.POINT_BALANCE
          credit.account shouldBe AccountCode.POINT_FUNDING
          debit.amount.compareTo(BigDecimal("200")) shouldBe 0

          // 4) 정합성: 전역 균형 + POINT_BALANCE 전역합 == Σ PointAccount.balance
          val verification = verificationService.verify()
          verification.isBalanced shouldBe true
          verification.pointBalanceMatches shouldBe true
      }
  }
  ```
  주: `verification.pointBalanceMatches`는 Task 4에서 추가되는 필드다. **Task 3 시점에는 이 테스트가 컴파일되지 않는다** — Step 4에서는 적립 통합만 먼저 확인하기 위해 이 한 줄을 임시로 제거하지 말고, **Task 3은 Task 4 완료 후 함께 실행**한다(아래 Step 5 참고). 컴파일 의존성 때문에 Task 3과 Task 4를 같은 브랜치에서 연속 수행한다.

- [ ] **Step 2: `VoucherRedemptionService` 생성자에 `PointEarnService` 주입**
  `src/main/kotlin/com/komsco/voucher/voucher/application/VoucherRedemptionService.kt` 상단 import에 추가:
  ```kotlin
  import com.commerce.point.application.PointEarnService
  ```
  생성자(22-30)의 `private val transactionTemplate: TransactionTemplate,` 줄 **앞**에 파라미터 추가:
  ```kotlin
      private val pointEarnService: PointEarnService,
  ```
  결과(생성자):
  ```kotlin
  @Service
  class VoucherRedemptionService(
      private val voucherRepository: VoucherJpaRepository,
      private val lockManager: VoucherLockManager,
      private val ledgerService: LedgerService,
      private val transactionService: TransactionService,
      private val eventPublisher: ApplicationEventPublisher,
      private val meterRegistry: MeterRegistry,
      private val pointEarnService: PointEarnService,
      private val transactionTemplate: TransactionTemplate,
  ) {
  ```

- [ ] **Step 3: redeem 트랜잭션 내부에서 적립 동기 호출**
  같은 파일에서 `tx.complete()` (line 65) **직후**, `eventPublisher.publishEvent(...)` **앞**에 다음을 삽입:
  ```kotlin
                  // 포인트 적립(동기, 같은 redemption txId 공유).
                  // 적립 기준액 = 실제 결제액(plain redeem에서는 amount; 쿠폰 적용 시 오케스트레이터가 T−D 전달).
                  // 포인트 결제분 제외(point-on-point) 및 적립 취소 회수는 STRETCH.
                  pointEarnService.earn(
                      memberId = voucher.memberId,
                      baseAmount = amount,
                      sourceTransactionId = tx.id,
                  )
  ```
  (삽입 위치는 `transactionTemplate.execute { _ -> ... }` 람다 내부이므로 같은 DB 트랜잭션에서 실행된다.)

- [ ] **Step 4: 기존 E2E 카운트 보정 (8 → 12)**
  `src/test/kotlin/com/komsco/voucher/integration/E2EFlowTest.kt` lines 90-96의 블록을 교체:
  교체 전:
  ```kotlin
          // 6. 원장 엔트리 수 확인: 발행(2) + 결제1(2) + 결제2(2) + 환불(2) = 8
          val allEntries = ledgerRepository.findAll()
              .filter { entry ->
                  transactionRepository.findById(entry.transactionId)
                      .map { it.voucherId == voucher.id }.orElse(false)
              }
          allEntries.size shouldBe 8
  ```
  교체 후:
  ```kotlin
          // 6. 원장 엔트리 수 확인: 발행(2) + 결제1[결제(2)+적립(2)] + 결제2[결제(2)+적립(2)] + 환불(2) = 12
          //    포인트 적립이 결제 트랜잭션과 동기로 같은 txId에 POINT_EARN 2-leg를 추가하므로 8 → 12.
          val allEntries = ledgerRepository.findAll()
              .filter { entry ->
                  transactionRepository.findById(entry.transactionId)
                      .map { it.voucherId == voucher.id }.orElse(false)
              }
          allEntries.size shouldBe 12
  ```

- [ ] **Step 5: (Task 4 완료 후) 통합 테스트 실행**
  > Task 3의 적립 통합 테스트는 `pointBalanceMatches`(Task 4)에 의존하므로, **Task 4의 Step 1~2(검증 서비스 확장)를 먼저 적용한 뒤** 본 Step을 실행한다. 두 태스크는 동일 브랜치에서 연속 수행한다.
  실행:
  ```bash
  ./gradlew test --tests "com.commerce.integration.PointEarnIntegrationTest"
  ./gradlew test --tests "com.commerce.integration.E2EFlowTest"
  ```
  기대: 둘 다 **PASS** (적립 200원 검증; E2E 12개 엔트리). Docker 데몬 필요.

- [ ] **Step 6: 커밋**
  실행:
  ```bash
  git add -A && git commit -m "$(cat <<'EOF'
feat(point): redeem 트랜잭션과 동기로 포인트 적립 통합

- VoucherRedemptionService에서 PointEarnService.earn 동기 호출(같은 txId)
- E2EFlowTest 원장 엔트리 카운트 8 -> 12 보정(POINT_EARN 2-leg)
- PointEarnIntegrationTest: 적립/잔액/원장쌍/정합성 검증

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
EOF
)"
  ```

---

### Task 4: 전역 정합성 불변식 추가 (`POINT_BALANCE` 전역합 == Σ PointAccount.balance)

**Files:**
- Modify `src/main/kotlin/com/komsco/voucher/ledger/application/LedgerVerificationService.kt` (`VerificationResult` 15-20, 생성자 30-34, `verify()` 50-63, `scheduledVerification` 37-48)
- Create `src/test/kotlin/com/komsco/voucher/integration/PointReconciliationTest.kt`

**Interfaces:**
- Consumes: `PointAccountJpaRepository.sumAllBalances()`, `PointAccountJpaRepository.overwriteBalance(...)`, `LedgerJpaRepository.sumByAccountAndSide(account, side)`, `AccountCode.POINT_BALANCE`, `LedgerEntrySide`.
- Produces: `VerificationResult.pointBalanceMatches: Boolean` (신규 필드, `isBalanced`에 AND).

- [ ] **Step 1: (TDD) 실패하는 정합성 회귀 테스트 작성**
  `src/test/kotlin/com/komsco/voucher/integration/PointReconciliationTest.kt`:
  ```kotlin
  package com.commerce.integration

  import com.commerce.ledger.application.LedgerVerificationService
  import com.commerce.point.infrastructure.PointAccountJpaRepository
  import com.commerce.support.IntegrationTestSupport
  import com.commerce.support.TestFixtures
  import com.commerce.voucher.application.VoucherRedemptionService
  import io.kotest.matchers.shouldBe
  import org.junit.jupiter.api.BeforeEach
  import org.junit.jupiter.api.Test
  import org.springframework.beans.factory.annotation.Autowired
  import org.springframework.transaction.support.TransactionTemplate
  import java.math.BigDecimal
  import java.util.UUID

  class PointReconciliationTest : IntegrationTestSupport() {

      @Autowired lateinit var fixtures: TestFixtures
      @Autowired lateinit var redemptionService: VoucherRedemptionService
      @Autowired lateinit var pointAccountRepository: PointAccountJpaRepository
      @Autowired lateinit var verificationService: LedgerVerificationService
      @Autowired lateinit var transactionTemplate: TransactionTemplate

      private var regionId: Long = 0
      private var memberId: Long = 0
      private var merchantId: Long = 0

      @BeforeEach
      fun setup() {
          val region = fixtures.createRegion(code = UUID.randomUUID().toString().take(2).uppercase())
          val member = fixtures.createMember()
          val owner = fixtures.createMember()
          val merchant = fixtures.createMerchant(region, owner)
          regionId = region.id
          memberId = member.id
          merchantId = merchant.id
      }

      @Test
      fun `point invariant holds after earn and breaks when the cache is corrupted`() {
          val voucher = fixtures.issueVoucher(memberId, regionId, BigDecimal("50000"))
          redemptionService.redeem(voucher.id, merchantId, BigDecimal("20000")) // +200 points

          val ok = verificationService.verify()
          ok.pointBalanceMatches shouldBe true
          ok.isBalanced shouldBe true

          // 캐시 잔액을 의도적으로 오염시켜 원장 net(200)과 불일치(201)로 만든다.
          transactionTemplate.executeWithoutResult {
              pointAccountRepository.overwriteBalance(memberId, BigDecimal("201"))
          }

          val broken = verificationService.verify()
          broken.pointBalanceMatches shouldBe false
          broken.isBalanced shouldBe false
      }
  }
  ```

- [ ] **Step 2: `LedgerVerificationService` 확장 (전체 파일 교체)**
  `src/main/kotlin/com/komsco/voucher/ledger/application/LedgerVerificationService.kt`:
  ```kotlin
  package com.commerce.ledger.application

  import com.commerce.ledger.domain.AccountCode
  import com.commerce.ledger.domain.LedgerEntrySide
  import com.commerce.ledger.infrastructure.LedgerJpaRepository
  import com.commerce.point.infrastructure.PointAccountJpaRepository
  import com.commerce.voucher.infrastructure.VoucherJpaRepository
  import io.micrometer.core.instrument.MeterRegistry
  import org.slf4j.LoggerFactory
  import org.springframework.scheduling.annotation.Scheduled
  import org.springframework.stereotype.Service
  import org.springframework.transaction.annotation.Isolation
  import org.springframework.transaction.annotation.Transactional
  import java.math.BigDecimal

  data class VerificationResult(
      val isBalanced: Boolean,
      val globalDebitTotal: BigDecimal,
      val globalCreditTotal: BigDecimal,
      val imbalancedVouchers: List<ImbalancedVoucher>,
      val pointBalanceMatches: Boolean,
  )

  data class ImbalancedVoucher(
      val voucherId: Long,
      val cachedBalance: BigDecimal,
      val ledgerBalance: BigDecimal,
      val difference: BigDecimal,
  )

  @Service
  class LedgerVerificationService(
      private val ledgerRepository: LedgerJpaRepository,
      private val voucherRepository: VoucherJpaRepository,
      private val pointAccountRepository: PointAccountJpaRepository,
      private val meterRegistry: MeterRegistry,
  ) {
      private val log = LoggerFactory.getLogger(javaClass)

      @Scheduled(cron = "0 0 2 * * *")
      @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
      fun scheduledVerification() {
          val result = verify()
          meterRegistry.gauge("ledger.verification.imbalance", result.imbalancedVouchers.size.toDouble())
          meterRegistry.gauge("ledger.verification.point.matches", if (result.pointBalanceMatches) 1.0 else 0.0)
          if (!result.isBalanced) {
              log.error(
                  "LEDGER IMBALANCE DETECTED: {} vouchers, pointBalanceMatches={}, global debit={}, credit={}",
                  result.imbalancedVouchers.size, result.pointBalanceMatches,
                  result.globalDebitTotal, result.globalCreditTotal,
              )
          } else {
              log.info("Ledger verification passed. Global balance: {}", result.globalDebitTotal)
          }
      }

      fun verify(): VerificationResult {
          val globalDebit = ledgerRepository.sumBySide(LedgerEntrySide.DEBIT)
          val globalCredit = ledgerRepository.sumBySide(LedgerEntrySide.CREDIT)
          val globalBalanced = globalDebit.compareTo(globalCredit) == 0

          val imbalanced = checkVoucherBalances()

          val pointBalanceMatches = checkPointBalance()

          return VerificationResult(
              isBalanced = globalBalanced && imbalanced.isEmpty() && pointBalanceMatches,
              globalDebitTotal = globalDebit,
              globalCreditTotal = globalCredit,
              imbalancedVouchers = imbalanced,
              pointBalanceMatches = pointBalanceMatches,
          )
      }

      private fun checkVoucherBalances(): List<ImbalancedVoucher> {
          val vouchers = voucherRepository.findAll()
          return vouchers.mapNotNull { voucher ->
              // VOUCHER_BALANCE 계정의 net balance = debit(발행,취소복원) - credit(사용,환불,만료)
              val ledgerBalance = ledgerRepository.netBalanceByVoucherAndAccount(
                  voucher.id, AccountCode.VOUCHER_BALANCE
              )
              val cachedBalance = voucher.balance
              if (cachedBalance.compareTo(ledgerBalance) != 0) {
                  ImbalancedVoucher(
                      voucherId = voucher.id,
                      cachedBalance = cachedBalance,
                      ledgerBalance = ledgerBalance,
                      difference = cachedBalance - ledgerBalance,
                  )
              } else null
          }
      }

      // POINT_BALANCE 차변정상: 원장 net(차변-대변) == 모든 PointAccount.balance 합.
      private fun checkPointBalance(): Boolean {
          val ledgerPointBalance =
              ledgerRepository.sumByAccountAndSide(AccountCode.POINT_BALANCE, LedgerEntrySide.DEBIT) -
              ledgerRepository.sumByAccountAndSide(AccountCode.POINT_BALANCE, LedgerEntrySide.CREDIT)
          val cachedPointTotal = pointAccountRepository.sumAllBalances()
          return cachedPointTotal.compareTo(ledgerPointBalance) == 0
      }
  }
  ```

- [ ] **Step 3: 정합성 회귀 테스트 통과 확인**
  실행: `./gradlew test --tests "com.commerce.integration.PointReconciliationTest"`
  기대: **PASS** (적립 후 `pointBalanceMatches=true`/`isBalanced=true`; 오염 후 둘 다 `false`). Docker 데몬 필요.

- [ ] **Step 4: Task 3 통합 테스트 동반 실행(이제 컴파일 가능)**
  실행:
  ```bash
  ./gradlew test --tests "com.commerce.integration.PointEarnIntegrationTest"
  ./gradlew test --tests "com.commerce.integration.E2EFlowTest"
  ./gradlew test --tests "com.commerce.integration.ConcurrencyTest"
  ```
  기대: 모두 **PASS** (`ConcurrencyTest`는 동일 바우처 직렬화로 회원 포인트 계정 경합 없음, `isBalanced` 유지).

- [ ] **Step 5: 커밋**
  실행:
  ```bash
  git add -A && git commit -m "$(cat <<'EOF'
feat(ledger): POINT_BALANCE 전역 정합성 불변식 추가

- VerificationResult.pointBalanceMatches 필드를 isBalanced에 AND
- netBalanceByAccount(POINT_BALANCE) == Σ PointAccount.balance 검증
- PointReconciliationTest: 정상 true, 캐시 오염 시 false

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
EOF
)"
  ```

---

### Task 5: 포인트 조회 API (`GET /api/v1/members/{memberId}/points`)

**Files:**
- Modify `src/main/kotlin/com/komsco/voucher/common/exception/ErrorCode.kt` (line 41 다음, enum 종료 `}` 앞)
- Create `src/main/kotlin/com/komsco/voucher/point/application/PointQueryService.kt`
- Create `src/main/kotlin/com/komsco/voucher/point/interfaces/dto/PointResponse.kt`
- Create `src/main/kotlin/com/komsco/voucher/point/interfaces/PointController.kt`

**Interfaces:**
- Consumes: `PointAccountJpaRepository.findByMemberId`, `PointTransactionJpaRepository.findByMemberIdOrderByCreatedAtDesc`, `BusinessException`, `ErrorCode`, `SecurityUtils.currentMemberId(): Long` (Plan 1 `com.commerce.common.security.SecurityUtils`; 미인증 시 `UNAUTHORIZED`).
- Produces:
  - `ErrorCode.POINT_ACCOUNT_NOT_FOUND`, `ErrorCode.ACCESS_DENIED`
  - `data class PointBalanceResponse`, `data class PointTransactionResponse`
  - `class PointQueryService.getBalance(memberId: Long): PointBalanceResponse`
  - `class PointController` — `GET /api/v1/members/{memberId}/points`

- [ ] **Step 1: `ErrorCode`에 신규 코드 추가**
  `src/main/kotlin/com/komsco/voucher/common/exception/ErrorCode.kt` 의 `MANUAL_ADJUSTMENT_REQUIRES_ADMIN(...)` 줄 **다음**, enum 종료 `}` **앞**에 추가:
  ```kotlin

      // Auth — 본인 자원 외 접근(FORBIDDEN, 403) 전용. 미인증(UNAUTHORIZED, 401)은 SecurityUtils.currentMemberId()가 던진다.
      ACCESS_DENIED(HttpStatus.FORBIDDEN, "본인 자원에만 접근할 수 있습니다"),

      // Point
      POINT_ACCOUNT_NOT_FOUND(HttpStatus.NOT_FOUND, "포인트 계정을 찾을 수 없습니다"),
  ```
  주: Plan 1/2가 동등한 forbidden 코드(예: `ACCESS_DENIED`)를 이미 추가했다면 **중복 추가하지 말고 기존 것을 재사용**한다(enum 값 중복 방지).

- [ ] **Step 2: `PointQueryService` 작성**
  `src/main/kotlin/com/komsco/voucher/point/application/PointQueryService.kt`:
  ```kotlin
  package com.commerce.point.application

  import com.commerce.common.exception.BusinessException
  import com.commerce.common.exception.ErrorCode
  import com.commerce.point.interfaces.dto.PointBalanceResponse
  import com.commerce.point.infrastructure.PointAccountJpaRepository
  import com.commerce.point.infrastructure.PointTransactionJpaRepository
  import org.springframework.stereotype.Service
  import org.springframework.transaction.annotation.Transactional

  @Service
  @Transactional(readOnly = true)
  class PointQueryService(
      private val pointAccountRepository: PointAccountJpaRepository,
      private val pointTransactionRepository: PointTransactionJpaRepository,
  ) {

      fun getBalance(memberId: Long): PointBalanceResponse {
          val account = pointAccountRepository.findByMemberId(memberId)
              ?: throw BusinessException(ErrorCode.POINT_ACCOUNT_NOT_FOUND)
          val history = pointTransactionRepository.findByMemberIdOrderByCreatedAtDesc(memberId)
          return PointBalanceResponse.of(account, history)
      }
  }
  ```
  주: 적립 이력이 전혀 없는 회원은 계정이 없어 `POINT_ACCOUNT_NOT_FOUND`(404)를 반환한다(설계 결정; "0 잔액 반환"으로 바꾸려면 `?: return PointBalanceResponse(memberId, ZERO, emptyList())` 한 줄 교체).

- [ ] **Step 3: 응답 DTO 작성**
  `src/main/kotlin/com/komsco/voucher/point/interfaces/dto/PointResponse.kt`:
  ```kotlin
  package com.commerce.point.interfaces.dto

  import com.commerce.point.domain.PointAccount
  import com.commerce.point.domain.PointTransaction
  import java.math.BigDecimal

  data class PointBalanceResponse(
      val memberId: Long,
      val balance: BigDecimal,
      val history: List<PointTransactionResponse>,
  ) {
      companion object {
          fun of(account: PointAccount, history: List<PointTransaction>) = PointBalanceResponse(
              memberId = account.memberId,
              balance = account.balance,
              history = history.map { PointTransactionResponse.from(it) },
          )
      }
  }

  data class PointTransactionResponse(
      val id: Long,
      val type: String,
      val amount: BigDecimal,
      val balanceAfter: BigDecimal,
      val sourceTransactionId: Long,
      val createdAt: String,
  ) {
      companion object {
          fun from(t: PointTransaction) = PointTransactionResponse(
              id = t.id,
              type = t.type.name,
              amount = t.amount,
              balanceAfter = t.balanceAfter,
              sourceTransactionId = t.sourceTransactionId,
              createdAt = t.createdAt.toString(),
          )
      }
  }
  ```

- [ ] **Step 4: `PointController` 작성 (`SecurityUtils` 기반 인증 + 본인 인가)**
  `src/main/kotlin/com/komsco/voucher/point/interfaces/PointController.kt`. 인증은 Plan 1 `SecurityUtils.currentMemberId()`(미인증 시 `UNAUTHORIZED`)에 위임하고, `ACCESS_DENIED`는 경로 memberId가 인증 주체와 다른 **FORBIDDEN(403)** 케이스에만 사용한다(별도 private `currentMemberId()`를 재작성하지 않는다):
  ```kotlin
  package com.commerce.point.interfaces

  import com.commerce.common.exception.BusinessException
  import com.commerce.common.exception.ErrorCode
  import com.commerce.common.security.SecurityUtils
  import com.commerce.point.application.PointQueryService
  import com.commerce.point.interfaces.dto.PointBalanceResponse
  import org.springframework.web.bind.annotation.GetMapping
  import org.springframework.web.bind.annotation.PathVariable
  import org.springframework.web.bind.annotation.RequestMapping
  import org.springframework.web.bind.annotation.RestController

  @RestController
  @RequestMapping("/api/v1/members/{memberId}/points")
  class PointController(
      private val pointQueryService: PointQueryService,
  ) {

      @GetMapping
      fun getPoints(@PathVariable memberId: Long): PointBalanceResponse {
          // 인증: SecurityUtils.currentMemberId()(Plan 1) — 미인증이면 UNAUTHORIZED(401)를 던진다.
          // 인가: 경로 memberId가 인증 주체와 다르면 본인 자원이 아니므로 ACCESS_DENIED(403). body/path 미신뢰.
          if (SecurityUtils.currentMemberId() != memberId) throw BusinessException(ErrorCode.ACCESS_DENIED)
          return pointQueryService.getBalance(memberId)
      }
  }
  ```
  주: `SecurityUtils.currentMemberId()`는 Plan 1 `JwtAuthenticationFilter`가 보안 컨텍스트에 채운 principal을 읽으므로, HTTP+JWT 경로 인가 테스트는 그 필터가 보안 체인에 연결된 뒤에야 가능하다(교차-plan) — 본 계획에서는 컨트롤러 단위 인증 테스트를 작성하지 않는다. 조회 로직(`PointQueryService`)은 Task 3의 통합 테스트가 적립을 만든 상태에서 다음 Step의 스모크로 컨텍스트 로딩을 확인한다.

- [ ] **Step 5: 컴파일 + 컨텍스트 로딩 스모크 확인**
  실행:
  ```bash
  ./gradlew compileKotlin compileTestKotlin
  ./gradlew test --tests "com.commerce.integration.PointEarnIntegrationTest"
  ```
  기대: 컴파일 성공; 통합 테스트 **PASS** (신규 `PointController`/`PointQueryService` 빈 포함 컨텍스트 정상 로딩). Docker 데몬 필요.

- [ ] **Step 6: 커밋**
  실행:
  ```bash
  git add -A && git commit -m "$(cat <<'EOF'
feat(point): 포인트 조회 API + 신규 ErrorCode 추가

- GET /api/v1/members/{memberId}/points (principal 기반 인가)
- PointQueryService.getBalance(잔액+이력)
- ErrorCode.POINT_ACCOUNT_NOT_FOUND / ACCESS_DENIED

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
EOF
)"
  ```

---

### Task 6: 쿠폰 결제(결합 오케스트레이터)에도 동기 포인트 적립 통합 (T−D 기준) — Plan 2 의존

> **교차-plan(Plan 2 의존).** Plan 2의 `RedemptionOrchestrator`(쿠폰 결합 결제)가 **이미 존재**할 때만 수행한다. Plan 2가 아직 없으면 이 태스크를 건너뛴다(plain redeem 적립은 Task 3에서 이미 보장). Task 3가 `VoucherRedemptionService.redeem`에 적립을 주입한 것과 **동일한 패턴**을 쿠폰 경로(오케스트레이터)에 적용한다. 적립 기준액은 쿠폰 할인 적용 후 **실제 결제액 `T−D`(`voucherCharged`)** 이며, 할인분 `D`(플랫폼 보조)는 적립 대상이 아니다.

**Files:**
- Modify `src/main/kotlin/com/komsco/voucher/promotion/application/RedemptionOrchestrator.kt` (Plan 2 산출물: 생성자 + `redeemWithCoupon` 트랜잭션 본문)
- Modify `src/test/kotlin/com/komsco/voucher/integration/CouponRedeemIntegrationTest.kt` (Plan 2 산출물: 같은 txId 원장 leg 카운트 4 → 6 보정)
- Create `src/test/kotlin/com/komsco/voucher/integration/CouponPointEarnIntegrationTest.kt`

**Interfaces:**
- Consumes: `PointEarnService.earn(memberId, baseAmount, sourceTransactionId)`(Task 2), `RedemptionOrchestrator.redeem(voucherId, merchantId, orderTotal, couponId)`(Plan 2), `Voucher.memberId`, `voucherCharged`(= `T−D`, redeemWithCoupon 지역변수), `tx.id`, `LedgerVerificationService.verify()`(Task 4 확장: `isBalanced`/`pointBalanceMatches`), `TestFixtures.createPromotion/issueCoupon/issueVoucher`(Plan 2).
- Produces: 쿠폰 결제 트랜잭션 내부에서 POINT_EARN 2-leg + PointAccount/PointTransaction 갱신(동기, redemption txId 공유).

- [ ] **Step 1: 선결 — Plan 2 오케스트레이터 존재 확인 (실행만, 코드 변경 없음)**
  실행:
  ```bash
  grep -n "class RedemptionOrchestrator" src/main/kotlin/com/komsco/voucher/promotion/application/RedemptionOrchestrator.kt
  ```
  기대: 매치 1줄. **매치가 없으면(=Plan 2 미적용) 이 태스크 전체를 건너뛴다** — plain redeem 적립은 Task 3에서 이미 보장된다. 매치되면 다음 단계로.

- [ ] **Step 2: (TDD) 실패하는 쿠폰 적립 통합 테스트 작성**
  `src/test/kotlin/com/komsco/voucher/integration/CouponPointEarnIntegrationTest.kt`:
  ```kotlin
  package com.commerce.integration

  import com.commerce.ledger.application.LedgerVerificationService
  import com.commerce.ledger.domain.AccountCode
  import com.commerce.ledger.domain.LedgerEntrySide
  import com.commerce.ledger.infrastructure.LedgerJpaRepository
  import com.commerce.point.domain.PointTransactionType
  import com.commerce.point.infrastructure.PointAccountJpaRepository
  import com.commerce.point.infrastructure.PointTransactionJpaRepository
  import com.commerce.promotion.application.RedemptionOrchestrator
  import com.commerce.promotion.domain.DiscountType
  import com.commerce.support.IntegrationTestSupport
  import com.commerce.support.TestFixtures
  import io.kotest.matchers.nulls.shouldNotBeNull
  import io.kotest.matchers.shouldBe
  import org.junit.jupiter.api.BeforeEach
  import org.junit.jupiter.api.Test
  import org.springframework.beans.factory.annotation.Autowired
  import java.math.BigDecimal
  import java.util.UUID

  class CouponPointEarnIntegrationTest : IntegrationTestSupport() {

      @Autowired lateinit var fixtures: TestFixtures
      @Autowired lateinit var orchestrator: RedemptionOrchestrator
      @Autowired lateinit var pointAccountRepository: PointAccountJpaRepository
      @Autowired lateinit var pointTransactionRepository: PointTransactionJpaRepository
      @Autowired lateinit var ledgerRepository: LedgerJpaRepository
      @Autowired lateinit var verificationService: LedgerVerificationService

      private var regionId: Long = 0
      private var memberId: Long = 0
      private var merchantId: Long = 0

      @BeforeEach
      fun setup() {
          val region = fixtures.createRegion(code = UUID.randomUUID().toString().take(2).uppercase())
          val member = fixtures.createMember()
          val merchant = fixtures.createMerchant(region, fixtures.createMember())
          regionId = region.id
          memberId = member.id
          merchantId = merchant.id
      }

      @Test
      fun `coupon redeem earns 1 percent points on T-D (actual paid amount) and stays balanced`() {
          val voucher = fixtures.issueVoucher(memberId, regionId, BigDecimal("50000"))
          val promotion = fixtures.createPromotion(
              discountType = DiscountType.FIXED, discountValue = BigDecimal("3000"),
              budgetLimit = BigDecimal("1000000"),
          )
          val coupon = fixtures.issueCoupon(promotion.id, memberId)

          // T=10,000, D=3,000 → voucherCharged(T−D)=7,000 → 적립 1% = 70원 (할인분 D는 적립 제외)
          val result = orchestrator.redeem(voucher.id, merchantId, BigDecimal("10000"), coupon.id)

          // 1) 캐시 잔액 = (T−D)의 1% = 70 (10,000의 1%=100 아님)
          val account = pointAccountRepository.findByMemberId(memberId)
          account.shouldNotBeNull()
          account.balance.compareTo(BigDecimal("70")) shouldBe 0

          // 2) append-only EARN 거래 (원 거래 link, 금액=70)
          val pointTxs = pointTransactionRepository.findBySourceTransactionId(result.transactionId)
          pointTxs.size shouldBe 1
          pointTxs[0].type shouldBe PointTransactionType.EARN
          pointTxs[0].amount.compareTo(BigDecimal("70")) shouldBe 0

          // 3) 같은 txId에 POINT_EARN 2-leg (DEBIT POINT_BALANCE / CREDIT POINT_FUNDING, 70)
          val pointEntries = ledgerRepository.findByTransactionId(result.transactionId)
              .filter { it.account == AccountCode.POINT_BALANCE || it.account == AccountCode.POINT_FUNDING }
          pointEntries.size shouldBe 2
          val debit = pointEntries.first { it.side == LedgerEntrySide.DEBIT }
          val credit = pointEntries.first { it.side == LedgerEntrySide.CREDIT }
          debit.account shouldBe AccountCode.POINT_BALANCE
          credit.account shouldBe AccountCode.POINT_FUNDING
          debit.amount.compareTo(BigDecimal("70")) shouldBe 0

          // 4) 정합성: 쿠폰 보조분(COUPON_SUBSIDY)과 포인트 적립(POINT_EARN)이 더해져도
          //    전역 균형 + POINT_BALANCE 전역합 == Σ PointAccount.balance 유지
          val verification = verificationService.verify()
          verification.isBalanced shouldBe true
          verification.pointBalanceMatches shouldBe true
      }
  }
  ```

- [ ] **Step 3: 실패 확인**
  실행: `./gradlew test --tests "com.commerce.integration.CouponPointEarnIntegrationTest"`
  기대: **FAIL** (오케스트레이터가 아직 적립을 호출하지 않아 `PointAccount` 미생성 → `findByMemberId`가 null → `shouldNotBeNull` 실패). Docker 데몬 필요.

- [ ] **Step 4: `RedemptionOrchestrator` 생성자에 `PointEarnService` 주입**
  `src/main/kotlin/com/komsco/voucher/promotion/application/RedemptionOrchestrator.kt` 상단 import에 추가:
  ```kotlin
  import com.commerce.point.application.PointEarnService
  ```
  생성자의 `private val transactionTemplate: TransactionTemplate,` 줄 **앞**에 파라미터 추가(Task 3가 `VoucherRedemptionService`에 주입한 것과 동일 패턴):
  ```kotlin
      private val pointEarnService: PointEarnService,
  ```
  결과(생성자):
  ```kotlin
  @Service
  class RedemptionOrchestrator(
      private val voucherRepository: VoucherJpaRepository,
      private val couponRepository: CouponJpaRepository,
      private val promotionRepository: PromotionJpaRepository,
      private val couponRedemptionRepository: CouponRedemptionJpaRepository,
      private val lockManager: VoucherLockManager,
      private val ledgerService: LedgerService,
      private val transactionService: TransactionService,
      private val budgetManager: PromotionBudgetManager,
      private val redemptionService: VoucherRedemptionService,
      private val meterRegistry: MeterRegistry,
      private val pointEarnService: PointEarnService,
      private val transactionTemplate: TransactionTemplate,
  ) {
  ```

- [ ] **Step 5: 쿠폰 결제 트랜잭션 내부에서 적립 동기 호출 (ledger 2쌍 분개 이후, 반환 직전)**
  같은 파일 `redeemWithCoupon`의 `transactionTemplate.execute { _ -> ... }` 람다에서, `tx.complete()` **직후**, `RedemptionResult(...)` 반환식 **앞**에 다음을 삽입:
  ```kotlin
                  // 포인트 적립(동기, 같은 redemption txId 공유). 적립 기준액 = 실제 결제액 T−D(voucherCharged).
                  // 할인분 D는 PROMOTION_FUNDING 보조분이므로 적립 대상이 아니다(전액 쿠폰 → voucherCharged=0이면 적립 0으로 미적립).
                  pointEarnService.earn(
                      memberId = voucher.memberId,
                      baseAmount = voucherCharged,
                      sourceTransactionId = tx.id,
                  )
  ```
  (`voucher`/`voucherCharged`/`tx`는 모두 같은 람다 스코프 내 변수이므로 같은 DB 트랜잭션에서 실행된다. `voucher.memberId == lockedCoupon.memberId`는 앞선 검증에서 보장됨.)

- [ ] **Step 6: 쿠폰 E2E 원장 leg 카운트 보정 (4 → 6)**
  Plan 2의 `src/test/kotlin/com/komsco/voucher/integration/CouponRedeemIntegrationTest.kt`에서, `coupon-applied redeem charges T-D ...` 테스트의 leg 카운트 단언을 교체한다(적립이 같은 txId에 POINT_EARN 2-leg를 추가하므로). Task 3 Step 4의 E2E 카운트 보정(8 → 12)과 동형:
  교체 전:
  ```kotlin
          // T-account: 같은 txId에 4개 leg(쌍1 REDEMPTION + 쌍2 COUPON_SUBSIDY)
          val entries = ledgerService.getEntriesByTransactionId(result.transactionId)
          entries.size shouldBe 4
  ```
  교체 후:
  ```kotlin
          // T-account: 같은 txId에 6개 leg(쌍1 REDEMPTION + 쌍2 COUPON_SUBSIDY + 적립 POINT_EARN 2-leg).
          //            Plan 3 Task 6이 오케스트레이터에 동기 적립을 추가하므로 4 → 6.
          val entries = ledgerService.getEntriesByTransactionId(result.transactionId)
          entries.size shouldBe 6
  ```
  주: 같은 테스트의 나머지 단언(MERCHANT_RECEIVABLE 차변 합=10,000, VOUCHER_BALANCE 대변 single=7,000, PROMOTION_FUNDING 대변 single=3,000, `debitSum==creditSum`)은 POINT_FUNDING/POINT_BALANCE가 별개 계정이라 그대로 통과한다. 클램프 테스트(`voucherCharged=0`)와 min-spend 거부 테스트는 적립액이 0이거나 결제가 일어나지 않아 leg 수 영향 없음.

- [ ] **Step 7: 통합 테스트 실행 → 통과(초록)**
  실행:
  ```bash
  ./gradlew test --tests "com.commerce.integration.CouponPointEarnIntegrationTest"
  ./gradlew test --tests "com.commerce.integration.CouponRedeemIntegrationTest"
  ./gradlew test --tests "com.commerce.integration.CouponConcurrencyTest"
  ```
  기대: 모두 **PASS** (쿠폰 적립 70원/잔액/원장쌍/정합성 검증; 기존 쿠폰 E2E는 6 leg로 통과; 동시성은 `isBalanced` 유지). Docker 데몬 필요.

- [ ] **Step 8: 커밋**
  실행:
  ```bash
  git add -A && git commit -m "$(cat <<'EOF'
feat(point): 쿠폰 결합 결제에도 동기 포인트 적립 통합(T−D 기준)

- RedemptionOrchestrator에 PointEarnService.earn 동기 호출(같은 txId, baseAmount=voucherCharged)
- CouponRedeemIntegrationTest 원장 leg 카운트 4 -> 6 보정(POINT_EARN 2-leg)
- CouponPointEarnIntegrationTest: 쿠폰 적립 T−D 기준 + isBalanced/pointBalanceMatches 유지

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
EOF
)"
  ```

---

## 완료 기준 (Definition of Done)
- `PointAccountTest`, `PointEarnServiceTest`, `PointEarnIntegrationTest`, `PointReconciliationTest`, `E2EFlowTest`, `ConcurrencyTest` 전부 PASS.
- (Plan 2 적용 시, Task 6) `CouponPointEarnIntegrationTest` PASS, `CouponRedeemIntegrationTest`(leg 4→6 보정)·`CouponConcurrencyTest`도 PASS 유지.
- 적립이 redeem 트랜잭션과 동기로 기록되고(after-commit 아님), POINT_EARN 분개가 redemption txId를 공유한다 — plain redeem(Task 3)·쿠폰 결합 결제(Task 6) 모두.
- `verify().isBalanced`가 `netBalanceByAccount(POINT_BALANCE) == Σ PointAccount.balance` 를 포함하며, 캐시 오염 시 false로 떨어진다. 쿠폰 적립이 더해져도 `isBalanced`/`pointBalanceMatches` 유지.
- 적립 기준액 = post-coupon 실제 결제액(plain redeem에서는 결제 금액 `amount`, 쿠폰 결제에서는 `T−D`=`voucherCharged`, 할인분 `D` 제외), 1원 단위 HALF_UP.
- 신규 컨트롤러(`PointController`)는 인증을 `SecurityUtils.currentMemberId()`(Plan 1, 미인증 시 `UNAUTHORIZED`)에 위임하고, `ACCESS_DENIED`는 본인 자원 외 접근(FORBIDDEN, 403)에만 사용한다(별도 private `currentMemberId()` 미작성).
- 범위 외(STRETCH)인 포인트 결제수단(tender)·회원별 정합성·만료 잡·적립 취소 회수·분산 point 락은 구현하지 않는다.
