# Plan 2 — COUPON / PROMOTION ENGINE (플래그십)

> 베이스 스펙: `docs/superpowers/specs/2026-06-29-commerce-repositioning-design.md` (§3 재무 모델, §4.1 쿠폰/프로모션, §4.3 결합결제 오케스트레이터, §4.4 취소/환불).
> 선행: **Plan 1**(`2026-06-29-plan-1-foundations.md`) 완료 필수. Plan 1이 추가한 계정/분개 타입/인증 헬퍼/Flyway validate 전환을 그대로 소비한다.

## Goal
쿠폰/프로모션 도메인을 기존 재무 무결성 디시플린(복식부기 원장·분산락·멱등성·정합성 검증)과 **동일하게** 얹는다.
- **(A) 도메인**: `Promotion` / `Coupon` / `CouponRedemption` 엔티티(`com.commerce.promotion.*`) + JPA 매핑 + `V2` Flyway 마이그레이션(Plan 1이 `validate`로 전환했으므로 필수).
- **(B) API**: 프로모션 생성(`POST /api/v1/promotions`), 멱등 쿠폰 발급(`POST /api/v1/promotions/{id}/coupons`), 회원 쿠폰 조회(`GET /api/v1/members/{memberId}/coupons`). 신원은 `SecurityUtils.currentMemberId()`(Plan 1)에서 도출.
- **(C) 예산**: redeem 시점 예약 — Redis Lua 원자 `INCRBY+한도체크+롤백`(기존 지역 카운터 패턴 미러) + 다운스트림 DB 실패 시 보상 `DECRBY`(try/finally) + `RegionCounterSyncScheduler` 미러링 재동기화 잡.
- **(D) 결합 오케스트레이터**: 바우처+쿠폰 단일 결제. 정준 락 순서 `coupon → voucher`. txId 공유 **2-leg 2쌍** 분개(바우처분 `REDEMPTION` + 플랫폼 보조분 `COUPON_SUBSIDY`). 기존 정합성 검증 그대로 통과.
- **(E) 규칙**: 단일 쿠폰만, `discount = min(discount, orderTotal)` 클램프, `min-spend` 미달 거부, 회원당 사용 한도, 0/음수 할인 거부.
- **(F) 취소/환불**: 두 leg 쌍 모두 역분개 + 바우처 잔액 복원 + 쿠폰 `CANCELLED` + 예산 반환.
- **(G) 테스트**: 단위(규칙), 통합(쿠폰 적용 결제 E2E), 동시성(동일 쿠폰 중복사용 0 · 예산 초과 0 · `isBalanced`), 멱등성(동일 Idempotency-Key → exactly-once), 원장 T-account 회귀.

## Architecture
- 패키지 루트 `com.commerce`. 신규 기능 패키지 `promotion` 하위 레이어 `interfaces / application / domain / infrastructure`(기존 컨벤션 검증됨).
- 원장은 2-leg 헬퍼(`LedgerService.record`)만 제공 → 3-leg가 필요한 쿠폰 결제는 **동일 `transactionId`를 공유하는 균형 2-leg 분개 2번**으로 표현(헬퍼 시그니처 불변, 스펙 §3.2).
- 예산 카운터는 Redis(`promotion:budget:{id}`) 원자 카운터 + Lua. DB 진실원천은 append-only `CouponRedemption.discountAmount` 합계 → 재동기화 잡이 Redis를 DB 기준으로 보정(지역 카운터가 `voucher` 합계로 보정하는 패턴과 동형).
- 결합 결제 흐름: `withCouponLock(couponId) { withVoucherLock(voucherId) { 예산예약 → DB tx(쿠폰검증/바우처차감/2쌍 분개/쿠폰 REDEEMED/CouponRedemption 기록/tx.complete) → 실패 시 보상 DECRBY } }`.
- 신규 결제 진입점은 기존 `POST /api/v1/vouchers/{id}/redeem`를 확장(`RedeemRequest.couponId` 추가)하여 `@Idempotent`·기존 멱등 인프라를 그대로 재사용.

## Tech Stack
- Gradle Kotlin DSL, Spring Boot 3.2.5, Kotlin 1.9.23, Java 17, Hibernate 6.4(부트 3.2 동봉), Redisson(분산락·Lua).
- MySQL 8 / Redis 7, Testcontainers 1.19.7, Kotest 5.8.1, MockK 1.13.10.
- 단일 테스트 실행: `./gradlew test --tests "com.commerce.<FqcnTest>"`. 통합/동시성/멱등성 테스트는 **Docker 데몬 필요**(Testcontainers MySQL+Redis).

## Global Constraints
- **LOCKED CONTRACT 준수(글자 그대로)**:
  - `LedgerService.record(debitAccount, creditAccount, amount, transactionId, entryType): List<LedgerEntry>` — 2-leg 전용. 3-leg는 txId 공유 2쌍 호출.
  - `LedgerService.netBalanceByAccount(account): BigDecimal`(= 차변−대변), `getEntriesByTransactionId(transactionId): List<LedgerEntry>`.
  - `transactionService.create(type, amount, voucherId?, merchantId?, memberId?, originalTransactionId?): Transaction`, `tx.complete()`, `tx.requestCancel()`, `tx.cancel()`.
  - `VoucherLockManager.withVoucherLock(id){}` / `withMemberPurchaseLock(memberId){}` + Plan 2 신규 `withCouponLock(couponId){}`(동일 private `withLock("coupon:$id")` 패턴).
  - `VoucherRedemptionService.redeem(voucherId, merchantId, amount): RedemptionResult`(쿠폰 없을 때 위임).
  - 멱등성: 컨트롤러 메서드 `@Idempotent` + 클라이언트 `Idempotency-Key` 헤더(기존 인터셉터/`/api/**` 패스).
- **Plan 1 산출물 소비**: `AccountCode.PROMOTION_FUNDING`, `LedgerEntryType.COUPON_SUBSIDY`(쿠폰 보조 leg), `SecurityUtils.currentMemberId(): Long`, `SecurityUtils.currentMemberIdOrNull(): Long?`. **이미 추가됨**이므로 재정의 금지. (`POINT_*`/`POINT_EARN`은 Plan 3 영역 — 본 계획 미사용.)
- **재무 모델(확정, 스펙 §3.2)**: 플랫폼 펀딩 · 정산 gross. `transaction.amount = 가맹점 수취 총액(gross T)`. 바우처는 `T−D`만 차감. 쿠폰 결제 분개:
  - 쌍1: `record(DEBIT MERCHANT_RECEIVABLE, CREDIT VOUCHER_BALANCE, amount=T−D, txId, REDEMPTION)`
  - 쌍2: `record(DEBIT MERCHANT_RECEIVABLE, CREDIT PROMOTION_FUNDING, amount=D, txId, COUPON_SUBSIDY)`
  - 쿠폰 경로는 `D>0`를 강제하므로 쌍2 항상 존재(D==0 스킵은 쿠폰 없는 일반 경로에만 해당).
- **Flyway validate 대응(필수)**: Plan 1이 메인·테스트 프로파일을 `ddl-auto: validate` + `flyway.enabled: true`로 전환했다. **신규 엔티티는 `V2__promotion_coupon.sql`을 동반해야 통합 테스트가 부팅된다.** 컬럼 타입은 Hibernate 6 + `MySQLDialect` 매핑(스네이크케이스, `decimal(38,2)`, `datetime(6)`, enum=`varchar(20)`, Boolean=`bit`, id=`bigint auto_increment`, `@Version`=`bigint`)을 따른다(= `V1__baseline.sql`과 동형).
- **금액 단위**: 모든 금액은 **정수 원(won)**. 예산 Lua는 `longValueExact()`로 정수화(지역 카운터와 동일). 정률 할인은 `RoundingMode.DOWN`(과할인 방지)로 0원 단위 내림.
- **신규 엔드포인트는 body의 memberId를 신뢰하지 않는다** — `SecurityUtils.currentMemberId()` 사용(가맹점 인증은 STRETCH → redeem은 기존대로 body `merchantId` 유지하되 Notes에 문서화).
- **TDD**: 각 태스크는 실패 테스트 먼저(빨강) → 구현 → 통과(초록). 각 태스크 끝에서 git commit. 순수 도메인 규칙은 Spring 없는 단위 테스트, 분개/락/예산/멱등은 통합 테스트.
- **플레이스홀더 금지**: 모든 코드 스텝은 완전한 Kotlin. 모든 테스트 스텝은 정확한 gradle 명령 + 기대 PASS/FAIL.

---

### Task 1: 에러코드 · 할인/상태 enum · `Promotion` 엔티티 + 규칙 단위 테스트 (스펙 §4.1, §7)

**Files:**
- Modify: `src/main/kotlin/com/komsco/voucher/common/exception/ErrorCode.kt` (현재 39~41행 Ledger 섹션 뒤, 닫는 `}` 앞에 쿠폰/프로모션 코드 추가)
- Create: `src/main/kotlin/com/komsco/voucher/promotion/domain/DiscountType.kt`
- Create: `src/main/kotlin/com/komsco/voucher/promotion/domain/PromotionStatus.kt`
- Create: `src/main/kotlin/com/komsco/voucher/promotion/domain/Promotion.kt`
- Create: `src/test/kotlin/com/komsco/voucher/promotion/domain/PromotionTest.kt`

**Interfaces:**
- Consumes (locked contract): `BaseEntity`(`id`, `createdAt`, `updatedAt`, `@Version version`), `BusinessException(ErrorCode, message?)`, `enum ErrorCode(status, message)`.
- Produces:
  - `ErrorCode.COUPON_NOT_FOUND / COUPON_EXPIRED / COUPON_ALREADY_USED / COUPON_USAGE_LIMIT_EXCEEDED / PROMOTION_BUDGET_EXCEEDED / MIN_SPEND_NOT_MET / PROMOTION_NOT_ACTIVE / INVALID_DISCOUNT`.
  - `enum class DiscountType { FIXED, PERCENTAGE }`.
  - `enum class PromotionStatus { DRAFT, ACTIVE, PAUSED, ENDED }`.
  - `class Promotion(name, discountType, discountValue, minSpend, perMemberLimit, budgetLimit, startsAt, endsAt, status, stackable) : BaseEntity` with `calculateDiscount(orderTotal: BigDecimal): BigDecimal`, `isActive(now): Boolean`, `activate()`.

- [ ] **Step 1: 실패 단위 테스트 작성** — `src/test/kotlin/com/komsco/voucher/promotion/domain/PromotionTest.kt`:
  ```kotlin
  package com.commerce.promotion.domain

  import com.commerce.common.exception.BusinessException
  import com.commerce.common.exception.ErrorCode
  import io.kotest.assertions.throwables.shouldThrow
  import io.kotest.matchers.shouldBe
  import org.junit.jupiter.api.Test
  import java.math.BigDecimal
  import java.time.LocalDateTime

  class PromotionTest {

      private fun promotion(
          type: DiscountType = DiscountType.FIXED,
          value: BigDecimal = BigDecimal("3000"),
          minSpend: BigDecimal = BigDecimal.ZERO,
          perMemberLimit: Int = 1,
          budgetLimit: BigDecimal = BigDecimal("1000000"),
          status: PromotionStatus = PromotionStatus.ACTIVE,
          startsAt: LocalDateTime = LocalDateTime.now().minusDays(1),
          endsAt: LocalDateTime = LocalDateTime.now().plusDays(1),
      ) = Promotion(
          name = "여름 할인",
          discountType = type,
          discountValue = value,
          minSpend = minSpend,
          perMemberLimit = perMemberLimit,
          budgetLimit = budgetLimit,
          startsAt = startsAt,
          endsAt = endsAt,
          status = status,
      )

      @Test
      fun `fixed discount returns the fixed value`() {
          promotion(DiscountType.FIXED, BigDecimal("3000"))
              .calculateDiscount(BigDecimal("10000")).compareTo(BigDecimal("3000")) shouldBe 0
      }

      @Test
      fun `percentage discount floors to whole won (no over-discount)`() {
          // 10% of 10,999 = 1,099.9 -> DOWN -> 1,099
          promotion(DiscountType.PERCENTAGE, BigDecimal("10"))
              .calculateDiscount(BigDecimal("10999")).compareTo(BigDecimal("1099")) shouldBe 0
      }

      @Test
      fun `calculateDiscount may exceed order total (clamp is applied by orchestrator)`() {
          // 정액 3,000 > 주문 2,000 — 엔티티는 raw 값을 반환하고, 클램프는 결합 오케스트레이터가 수행한다.
          promotion(DiscountType.FIXED, BigDecimal("3000"))
              .calculateDiscount(BigDecimal("2000")).compareTo(BigDecimal("3000")) shouldBe 0
      }

      @Test
      fun `zero discount value is rejected with INVALID_DISCOUNT`() {
          shouldThrow<BusinessException> { promotion(value = BigDecimal.ZERO) }
              .errorCode shouldBe ErrorCode.INVALID_DISCOUNT
      }

      @Test
      fun `negative discount value is rejected with INVALID_DISCOUNT`() {
          shouldThrow<BusinessException> { promotion(value = BigDecimal("-1")) }
              .errorCode shouldBe ErrorCode.INVALID_DISCOUNT
      }

      @Test
      fun `isActive is false when status is not ACTIVE`() {
          promotion(status = PromotionStatus.PAUSED).isActive() shouldBe false
      }

      @Test
      fun `isActive is false outside the validity window`() {
          val now = LocalDateTime.now()
          promotion(startsAt = now.plusDays(1), endsAt = now.plusDays(2)).isActive(now) shouldBe false
          promotion(startsAt = now.minusDays(2), endsAt = now.minusDays(1)).isActive(now) shouldBe false
      }

      @Test
      fun `activate transitions DRAFT to ACTIVE`() {
          val p = promotion(status = PromotionStatus.DRAFT)
          p.activate()
          p.status shouldBe PromotionStatus.ACTIVE
      }
  }
  ```

- [ ] **Step 2: 테스트 실행 → 컴파일 실패 확인(빨강)** — 순수 단위 테스트, Docker 불필요.
  ```
  ./gradlew test --tests "com.commerce.promotion.domain.PromotionTest"
  ```
  기대: **FAIL** — `Unresolved reference: Promotion / DiscountType / PromotionStatus / INVALID_DISCOUNT`.

- [ ] **Step 3: `ErrorCode`에 쿠폰/프로모션 코드 추가** — `src/main/kotlin/com/komsco/voucher/common/exception/ErrorCode.kt`의 Ledger 섹션(`MANUAL_ADJUSTMENT_REQUIRES_ADMIN(...)` 줄) 다음, enum 닫는 `}` 앞에 블록을 추가한다(기존 항목 불변; Plan 1이 이미 추가했을 수 있는 항목은 중복 추가 금지):
  ```kotlin
      // Promotion / Coupon (스펙 §4.1, §7)
      PROMOTION_NOT_ACTIVE(HttpStatus.BAD_REQUEST, "진행 중인 프로모션이 아닙니다"),
      PROMOTION_BUDGET_EXCEEDED(HttpStatus.BAD_REQUEST, "프로모션 예산이 소진되었습니다"),
      INVALID_DISCOUNT(HttpStatus.BAD_REQUEST, "유효하지 않은 할인 금액입니다"),
      MIN_SPEND_NOT_MET(HttpStatus.BAD_REQUEST, "최소 결제금액 조건을 충족하지 않습니다"),
      COUPON_NOT_FOUND(HttpStatus.NOT_FOUND, "쿠폰을 찾을 수 없습니다"),
      COUPON_EXPIRED(HttpStatus.BAD_REQUEST, "만료된 쿠폰입니다"),
      COUPON_ALREADY_USED(HttpStatus.BAD_REQUEST, "이미 사용된 쿠폰입니다"),
      COUPON_USAGE_LIMIT_EXCEEDED(HttpStatus.BAD_REQUEST, "회원당 쿠폰 사용 한도를 초과했습니다"),
  ```

- [ ] **Step 4: `DiscountType` enum 작성** — `src/main/kotlin/com/komsco/voucher/promotion/domain/DiscountType.kt`:
  ```kotlin
  package com.commerce.promotion.domain

  enum class DiscountType {
      FIXED,      // 정액 할인 (원)
      PERCENTAGE, // 정률 할인 (%)
  }
  ```

- [ ] **Step 5: `PromotionStatus` enum 작성** — `src/main/kotlin/com/komsco/voucher/promotion/domain/PromotionStatus.kt`:
  ```kotlin
  package com.commerce.promotion.domain

  enum class PromotionStatus {
      DRAFT, ACTIVE, PAUSED, ENDED,
  }
  ```

- [ ] **Step 6: `Promotion` 엔티티 작성** — `src/main/kotlin/com/komsco/voucher/promotion/domain/Promotion.kt`. 0/음수 할인은 `BusinessException(INVALID_DISCOUNT)`(테스트가 기대), 나머지 불변식은 `require`(기존 엔티티 컨벤션):
  ```kotlin
  package com.commerce.promotion.domain

  import com.commerce.common.domain.BaseEntity
  import com.commerce.common.exception.BusinessException
  import com.commerce.common.exception.ErrorCode
  import jakarta.persistence.*
  import java.math.BigDecimal
  import java.math.RoundingMode
  import java.time.LocalDateTime

  @Entity
  @Table(
      name = "promotions",
      indexes = [
          Index(name = "idx_promotion_status", columnList = "status, startsAt, endsAt"),
      ]
  )
  class Promotion(
      @Column(nullable = false, length = 100)
      val name: String,

      @Enumerated(EnumType.STRING)
      @Column(nullable = false, length = 20)
      val discountType: DiscountType,

      @Column(nullable = false)
      val discountValue: BigDecimal,

      @Column(nullable = false)
      val minSpend: BigDecimal,

      @Column(nullable = false)
      val perMemberLimit: Int,

      @Column(nullable = false)
      val budgetLimit: BigDecimal,

      @Column(nullable = false)
      val startsAt: LocalDateTime,

      @Column(nullable = false)
      val endsAt: LocalDateTime,

      @Enumerated(EnumType.STRING)
      @Column(nullable = false, length = 20)
      var status: PromotionStatus = PromotionStatus.DRAFT,

      @Column(nullable = false)
      val stackable: Boolean = false, // MUST=false 고정 (스택은 STRETCH)
  ) : BaseEntity() {

      init {
          // 0/음수 할인 거부 (스펙 §4.1)
          if (discountValue <= BigDecimal.ZERO) throw BusinessException(ErrorCode.INVALID_DISCOUNT)
          require(budgetLimit > BigDecimal.ZERO) { "예산 한도는 0보다 커야 합니다" }
          require(minSpend >= BigDecimal.ZERO) { "최소 결제금액은 0 이상이어야 합니다" }
          require(perMemberLimit >= 1) { "회원당 사용 한도는 1 이상이어야 합니다" }
          require(endsAt.isAfter(startsAt)) { "종료일은 시작일 이후여야 합니다" }
      }

      /**
       * 주문총액에 대한 raw 할인액(클램프 전).
       * - FIXED: 정액
       * - PERCENTAGE: 주문총액 * 율 / 100, 0원 단위 내림(RoundingMode.DOWN, 과할인 방지)
       * 주문총액 초과(정액>주문) 가능 — 클램프 min(D, T)는 결합 오케스트레이터가 수행한다.
       */
      fun calculateDiscount(orderTotal: BigDecimal): BigDecimal = when (discountType) {
          DiscountType.FIXED -> discountValue
          DiscountType.PERCENTAGE ->
              orderTotal.multiply(discountValue).divide(BigDecimal(100), 0, RoundingMode.DOWN)
      }

      fun isActive(now: LocalDateTime = LocalDateTime.now()): Boolean =
          status == PromotionStatus.ACTIVE && !now.isBefore(startsAt) && now.isBefore(endsAt)

      fun activate() {
          if (status != PromotionStatus.DRAFT)
              throw BusinessException(ErrorCode.INVALID_STATE_TRANSITION)
          status = PromotionStatus.ACTIVE
      }
  }
  ```

- [ ] **Step 7: 테스트 재실행 → 통과(초록)** — Docker 불필요.
  ```
  ./gradlew test --tests "com.commerce.promotion.domain.PromotionTest"
  ```
  기대: **PASS** — 9개 테스트 통과.

- [ ] **Step 8: 커밋**
  ```
  git add src/main/kotlin/com/komsco/voucher/common/exception/ErrorCode.kt \
          src/main/kotlin/com/komsco/voucher/promotion/domain/DiscountType.kt \
          src/main/kotlin/com/komsco/voucher/promotion/domain/PromotionStatus.kt \
          src/main/kotlin/com/komsco/voucher/promotion/domain/Promotion.kt \
          src/test/kotlin/com/komsco/voucher/promotion/domain/PromotionTest.kt
  git commit -m "feat(promotion): Promotion 엔티티 + 할인 계산/검증 규칙 + 쿠폰 에러코드"
  ```

---

### Task 2: `Coupon` · `CouponRedemption` 엔티티 + 리포지토리 + V2 마이그레이션 + 영속성/규칙 테스트 (스펙 §4.1, §6.2)

**Files:**
- Create: `src/main/kotlin/com/komsco/voucher/promotion/domain/CouponStatus.kt`
- Create: `src/main/kotlin/com/komsco/voucher/promotion/domain/Coupon.kt`
- Create: `src/main/kotlin/com/komsco/voucher/promotion/domain/CouponRedemption.kt`
- Create: `src/main/kotlin/com/komsco/voucher/promotion/infrastructure/PromotionJpaRepository.kt`
- Create: `src/main/kotlin/com/komsco/voucher/promotion/infrastructure/CouponJpaRepository.kt`
- Create: `src/main/kotlin/com/komsco/voucher/promotion/infrastructure/CouponRedemptionJpaRepository.kt`
- Create: `src/main/resources/db/migration/V2__promotion_coupon.sql`
- Create: `src/test/kotlin/com/komsco/voucher/promotion/domain/CouponTest.kt`
- Create: `src/test/kotlin/com/komsco/voucher/promotion/PromotionPersistenceTest.kt`

**Interfaces:**
- Consumes: `Promotion`(Task 1), `BaseEntity`, `BusinessException`, `IntegrationTestSupport`, Plan 1 `V1__baseline.sql` + `flyway/validate` 프로파일.
- Produces:
  - `enum class CouponStatus { ISSUED, RESERVED, REDEEMED, EXPIRED, CANCELLED }`.
  - `class Coupon(promotionId, memberId, expiresAt, status) : BaseEntity` — `reserve()`, `redeem(now)`, `cancel()`, `expire()`, `isExpired(now)`.
  - `class CouponRedemption(couponId, promotionId, memberId, voucherId, transactionId, orderTotal, discountAmount, voucherCharged, cancelled) : BaseEntity` — `markCancelled()`.
  - `interface PromotionJpaRepository : JpaRepository<Promotion, Long>`.
  - `interface CouponJpaRepository : JpaRepository<Coupon, Long>` — `findByMemberId(memberId): List<Coupon>`.
  - `interface CouponRedemptionJpaRepository : JpaRepository<CouponRedemption, Long>` — `findByTransactionId(txId): CouponRedemption?`, `countByMemberIdAndPromotionIdAndCancelledFalse(memberId, promotionId): Long`, `sumActiveDiscountByPromotion(promotionId): BigDecimal`.
  - `classpath:db/migration/V2__promotion_coupon.sql`.

- [ ] **Step 1: 실패 단위 테스트 작성(쿠폰 상태 규칙)** — `src/test/kotlin/com/komsco/voucher/promotion/domain/CouponTest.kt`:
  ```kotlin
  package com.commerce.promotion.domain

  import com.commerce.common.exception.BusinessException
  import com.commerce.common.exception.ErrorCode
  import io.kotest.assertions.throwables.shouldThrow
  import io.kotest.matchers.shouldBe
  import org.junit.jupiter.api.Test
  import java.time.LocalDateTime

  class CouponTest {

      private fun coupon(
          status: CouponStatus = CouponStatus.ISSUED,
          expiresAt: LocalDateTime = LocalDateTime.now().plusDays(1),
      ) = Coupon(promotionId = 1L, memberId = 10L, expiresAt = expiresAt, status = status)

      @Test
      fun `redeem transitions ISSUED to REDEEMED`() {
          val c = coupon()
          c.redeem()
          c.status shouldBe CouponStatus.REDEEMED
      }

      @Test
      fun `redeem on a non-ISSUED coupon throws COUPON_ALREADY_USED`() {
          shouldThrow<BusinessException> { coupon(status = CouponStatus.REDEEMED).redeem() }
              .errorCode shouldBe ErrorCode.COUPON_ALREADY_USED
      }

      @Test
      fun `redeem on an expired coupon throws COUPON_EXPIRED`() {
          shouldThrow<BusinessException> {
              coupon(expiresAt = LocalDateTime.now().minusSeconds(1)).redeem()
          }.errorCode shouldBe ErrorCode.COUPON_EXPIRED
      }

      @Test
      fun `cancel transitions REDEEMED to CANCELLED`() {
          val c = coupon(status = CouponStatus.REDEEMED)
          c.cancel()
          c.status shouldBe CouponStatus.CANCELLED
      }

      @Test
      fun `cancel on a non-REDEEMED coupon throws INVALID_STATE_TRANSITION`() {
          shouldThrow<BusinessException> { coupon(status = CouponStatus.ISSUED).cancel() }
              .errorCode shouldBe ErrorCode.INVALID_STATE_TRANSITION
      }
  }
  ```

- [ ] **Step 2: 단위 테스트 실행 → 컴파일 실패(빨강)** — Docker 불필요.
  ```
  ./gradlew test --tests "com.commerce.promotion.domain.CouponTest"
  ```
  기대: **FAIL** — `Unresolved reference: Coupon / CouponStatus`.

- [ ] **Step 3: `CouponStatus` enum 작성** — `src/main/kotlin/com/komsco/voucher/promotion/domain/CouponStatus.kt`:
  ```kotlin
  package com.commerce.promotion.domain

  enum class CouponStatus {
      ISSUED,    // 발급됨 (사용 가능)
      RESERVED,  // 예약됨 (STRETCH 비동기 예약 흐름 전용; MUST 동기 흐름은 ISSUED→REDEEMED 직행)
      REDEEMED,  // 사용 완료
      EXPIRED,   // 만료
      CANCELLED, // 취소(결제 취소로 회수)
  }
  ```

- [ ] **Step 4: `Coupon` 엔티티 작성** — `src/main/kotlin/com/komsco/voucher/promotion/domain/Coupon.kt`:
  ```kotlin
  package com.commerce.promotion.domain

  import com.commerce.common.domain.BaseEntity
  import com.commerce.common.exception.BusinessException
  import com.commerce.common.exception.ErrorCode
  import jakarta.persistence.*
  import java.time.LocalDateTime

  @Entity
  @Table(
      name = "coupons",
      indexes = [
          Index(name = "idx_coupon_member", columnList = "memberId, status"),
          Index(name = "idx_coupon_promotion", columnList = "promotionId"),
      ]
  )
  class Coupon(
      @Column(nullable = false)
      val promotionId: Long,

      @Column(nullable = false)
      val memberId: Long,

      @Column(nullable = false)
      val expiresAt: LocalDateTime,

      @Enumerated(EnumType.STRING)
      @Column(nullable = false, length = 20)
      var status: CouponStatus = CouponStatus.ISSUED,
  ) : BaseEntity() {

      /** STRETCH 비동기 예약 흐름 전용. MUST 동기 흐름에서는 사용하지 않는다. */
      fun reserve() {
          if (status != CouponStatus.ISSUED) throw BusinessException(ErrorCode.COUPON_ALREADY_USED)
          status = CouponStatus.RESERVED
      }

      fun redeem(now: LocalDateTime = LocalDateTime.now()) {
          if (status != CouponStatus.ISSUED) throw BusinessException(ErrorCode.COUPON_ALREADY_USED)
          if (isExpired(now)) throw BusinessException(ErrorCode.COUPON_EXPIRED)
          status = CouponStatus.REDEEMED
      }

      fun cancel() {
          if (status != CouponStatus.REDEEMED) throw BusinessException(ErrorCode.INVALID_STATE_TRANSITION)
          status = CouponStatus.CANCELLED
      }

      fun expire() {
          if (status != CouponStatus.ISSUED) throw BusinessException(ErrorCode.INVALID_STATE_TRANSITION)
          status = CouponStatus.EXPIRED
      }

      fun isExpired(now: LocalDateTime = LocalDateTime.now()): Boolean = expiresAt.isBefore(now)
  }
  ```

- [ ] **Step 5: `CouponRedemption` 엔티티 작성** — `src/main/kotlin/com/komsco/voucher/promotion/domain/CouponRedemption.kt`. append-only 추적 레코드(예산 재동기화의 DB 진실원천):
  ```kotlin
  package com.commerce.promotion.domain

  import com.commerce.common.domain.BaseEntity
  import jakarta.persistence.*
  import java.math.BigDecimal

  @Entity
  @Table(
      name = "coupon_redemptions",
      indexes = [
          Index(name = "idx_couponredemption_tx", columnList = "transactionId"),
          Index(name = "idx_couponredemption_member_promo", columnList = "memberId, promotionId, cancelled"),
      ]
  )
  class CouponRedemption(
      @Column(nullable = false) val couponId: Long,
      @Column(nullable = false) val promotionId: Long,
      @Column(nullable = false) val memberId: Long,
      @Column(nullable = false) val voucherId: Long,
      @Column(nullable = false) val transactionId: Long,
      @Column(nullable = false) val orderTotal: BigDecimal,     // T (gross)
      @Column(nullable = false) val discountAmount: BigDecimal, // D
      @Column(nullable = false) val voucherCharged: BigDecimal, // T - D
      @Column(nullable = false) var cancelled: Boolean = false,
  ) : BaseEntity() {
      fun markCancelled() { cancelled = true }
  }
  ```

- [ ] **Step 6: 리포지토리 3종 작성**
  - `src/main/kotlin/com/komsco/voucher/promotion/infrastructure/PromotionJpaRepository.kt`:
    ```kotlin
    package com.commerce.promotion.infrastructure

    import com.commerce.promotion.domain.Promotion
    import org.springframework.data.jpa.repository.JpaRepository

    interface PromotionJpaRepository : JpaRepository<Promotion, Long>
    ```
  - `src/main/kotlin/com/komsco/voucher/promotion/infrastructure/CouponJpaRepository.kt`:
    ```kotlin
    package com.commerce.promotion.infrastructure

    import com.commerce.promotion.domain.Coupon
    import org.springframework.data.jpa.repository.JpaRepository

    interface CouponJpaRepository : JpaRepository<Coupon, Long> {
        fun findByMemberId(memberId: Long): List<Coupon>
    }
    ```
  - `src/main/kotlin/com/komsco/voucher/promotion/infrastructure/CouponRedemptionJpaRepository.kt`:
    ```kotlin
    package com.commerce.promotion.infrastructure

    import com.commerce.promotion.domain.CouponRedemption
    import org.springframework.data.jpa.repository.JpaRepository
    import org.springframework.data.jpa.repository.Query
    import java.math.BigDecimal

    interface CouponRedemptionJpaRepository : JpaRepository<CouponRedemption, Long> {

        fun findByTransactionId(transactionId: Long): CouponRedemption?

        fun countByMemberIdAndPromotionIdAndCancelledFalse(memberId: Long, promotionId: Long): Long

        @Query("""
            SELECT COALESCE(SUM(cr.discountAmount), 0) FROM CouponRedemption cr
            WHERE cr.promotionId = :promotionId AND cr.cancelled = false
        """)
        fun sumActiveDiscountByPromotion(promotionId: Long): BigDecimal
    }
    ```

- [ ] **Step 7: `V2__promotion_coupon.sql` 작성(필수 — Plan 1이 validate로 전환했으므로 없으면 부팅 실패)** — `src/main/resources/db/migration/V2__promotion_coupon.sql`. 컬럼은 Hibernate 6 + MySQLDialect 매핑(스네이크케이스, `decimal(38,2)`, `datetime(6)`, enum=`varchar(20)`, Boolean=`bit`)을 따른다:
  ```sql
  create table promotions (
      id bigint not null auto_increment,
      created_at datetime(6) not null,
      updated_at datetime(6) not null,
      version bigint not null,
      name varchar(100) not null,
      discount_type varchar(20) not null,
      discount_value decimal(38,2) not null,
      min_spend decimal(38,2) not null,
      per_member_limit integer not null,
      budget_limit decimal(38,2) not null,
      starts_at datetime(6) not null,
      ends_at datetime(6) not null,
      status varchar(20) not null,
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
      status varchar(20) not null,
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
  ```

- [ ] **Step 8: 영속성/validate 부팅 테스트 작성** — `src/test/kotlin/com/komsco/voucher/promotion/PromotionPersistenceTest.kt`. 컨텍스트가 `flyway(V1,V2) → Hibernate validate` 경로로 부팅되는지 + 매핑 왕복을 검증:
  ```kotlin
  package com.commerce.promotion

  import com.commerce.promotion.domain.Coupon
  import com.commerce.promotion.domain.CouponRedemption
  import com.commerce.promotion.domain.CouponStatus
  import com.commerce.promotion.domain.DiscountType
  import com.commerce.promotion.domain.Promotion
  import com.commerce.promotion.domain.PromotionStatus
  import com.commerce.promotion.infrastructure.CouponJpaRepository
  import com.commerce.promotion.infrastructure.CouponRedemptionJpaRepository
  import com.commerce.promotion.infrastructure.PromotionJpaRepository
  import com.commerce.support.IntegrationTestSupport
  import io.kotest.matchers.shouldBe
  import org.junit.jupiter.api.Test
  import org.springframework.beans.factory.annotation.Autowired
  import java.math.BigDecimal
  import java.time.LocalDateTime

  class PromotionPersistenceTest : IntegrationTestSupport() {

      @Autowired lateinit var promotionRepository: PromotionJpaRepository
      @Autowired lateinit var couponRepository: CouponJpaRepository
      @Autowired lateinit var couponRedemptionRepository: CouponRedemptionJpaRepository

      @Test
      fun `promotion, coupon, redemption round-trip through validated schema`() {
          val promotion = promotionRepository.save(
              Promotion(
                  name = "왕복 테스트",
                  discountType = DiscountType.FIXED,
                  discountValue = BigDecimal("3000"),
                  minSpend = BigDecimal.ZERO,
                  perMemberLimit = 1,
                  budgetLimit = BigDecimal("1000000"),
                  startsAt = LocalDateTime.now().minusDays(1),
                  endsAt = LocalDateTime.now().plusDays(1),
                  status = PromotionStatus.ACTIVE,
              )
          )
          val coupon = couponRepository.save(
              Coupon(promotionId = promotion.id, memberId = 10L, expiresAt = promotion.endsAt)
          )
          couponRedemptionRepository.save(
              CouponRedemption(
                  couponId = coupon.id, promotionId = promotion.id, memberId = 10L,
                  voucherId = 1L, transactionId = 1L,
                  orderTotal = BigDecimal("10000"), discountAmount = BigDecimal("3000"),
                  voucherCharged = BigDecimal("7000"),
              )
          )

          couponRepository.findById(coupon.id).get().status shouldBe CouponStatus.ISSUED
          couponRedemptionRepository.sumActiveDiscountByPromotion(promotion.id)
              .compareTo(BigDecimal("3000")) shouldBe 0
          couponRedemptionRepository.countByMemberIdAndPromotionIdAndCancelledFalse(10L, promotion.id) shouldBe 1L
      }
  }
  ```

- [ ] **Step 9: 단위 + 통합 테스트 실행 → 통과(초록)** — 영속성 테스트는 **Docker 데몬 필요**.
  ```
  ./gradlew test --tests "com.commerce.promotion.domain.CouponTest" --tests "com.commerce.promotion.PromotionPersistenceTest"
  ```
  기대: **PASS**. 만약 `PromotionPersistenceTest`가 `SchemaManagementException`/`SchemaValidationException`(컬럼 타입·기본값 불일치)으로 실패하면 **V2 결함**이다 — `src/main/resources/db/migration/V1__baseline.sql`이 동종 컬럼(예: `decimal`/`datetime`/`bit`)을 어떤 형태로 emit했는지 확인해 `V2`의 해당 컬럼 정의를 일치시킨다. `ddl-auto`를 되돌리지 않는다(Plan 1 §6.2 의도: 불일치 즉시 노출·교정).

- [ ] **Step 10: 커밋**
  ```
  git add src/main/kotlin/com/komsco/voucher/promotion/domain/CouponStatus.kt \
          src/main/kotlin/com/komsco/voucher/promotion/domain/Coupon.kt \
          src/main/kotlin/com/komsco/voucher/promotion/domain/CouponRedemption.kt \
          src/main/kotlin/com/komsco/voucher/promotion/infrastructure/PromotionJpaRepository.kt \
          src/main/kotlin/com/komsco/voucher/promotion/infrastructure/CouponJpaRepository.kt \
          src/main/kotlin/com/komsco/voucher/promotion/infrastructure/CouponRedemptionJpaRepository.kt \
          src/main/resources/db/migration/V2__promotion_coupon.sql \
          src/test/kotlin/com/komsco/voucher/promotion/domain/CouponTest.kt \
          src/test/kotlin/com/komsco/voucher/promotion/PromotionPersistenceTest.kt
  git commit -m "feat(promotion): Coupon/CouponRedemption 엔티티 + 리포지토리 + V2 마이그레이션(validate 통과)"
  ```

---

### Task 3: 예산 매니저(Lua 원자 예약/보상) + 재동기화 스케줄러 (스펙 §4.1 예산 라이프사이클)

**Files:**
- Create: `src/main/kotlin/com/komsco/voucher/promotion/infrastructure/PromotionBudgetManager.kt`
- Create: `src/main/kotlin/com/komsco/voucher/promotion/application/PromotionBudgetSyncScheduler.kt`
- Create: `src/test/kotlin/com/komsco/voucher/promotion/PromotionBudgetManagerTest.kt`

**Interfaces:**
- Consumes: `RedissonClient`(+ `RScript`, `StringCodec` — `VoucherIssueService`의 Lua 사용 패턴 미러), `PromotionJpaRepository`, `CouponRedemptionJpaRepository.sumActiveDiscountByPromotion`.
- Produces:
  - `class PromotionBudgetManager` — `reserve(promotionId: Long, discount: BigDecimal, budgetLimit: BigDecimal): Boolean`(원자 INCRBY+한도; 초과 시 false), `release(promotionId: Long, discount: BigDecimal)`(보상/반환 DECRBY), `consumed(promotionId: Long): Long`.
  - `class PromotionBudgetSyncScheduler` — `@Scheduled(cron = "0 0 * * * *") syncPromotionBudgets()`(Redis를 DB 합계로 보정).

- [ ] **Step 1: 실패 통합 테스트 작성** — `src/test/kotlin/com/komsco/voucher/promotion/PromotionBudgetManagerTest.kt`. Redis 키 충돌 방지를 위해 무작위 promotionId 사용:
  ```kotlin
  package com.commerce.promotion

  import com.commerce.promotion.infrastructure.PromotionBudgetManager
  import com.commerce.support.IntegrationTestSupport
  import io.kotest.matchers.shouldBe
  import org.junit.jupiter.api.Test
  import org.springframework.beans.factory.annotation.Autowired
  import java.math.BigDecimal
  import kotlin.random.Random

  class PromotionBudgetManagerTest : IntegrationTestSupport() {

      @Autowired lateinit var budgetManager: PromotionBudgetManager

      @Test
      fun `reserve within limit succeeds and accumulates consumed`() {
          val promotionId = Random.nextLong(1, 1_000_000_000)
          budgetManager.reserve(promotionId, BigDecimal("3000"), BigDecimal("9000")) shouldBe true
          budgetManager.reserve(promotionId, BigDecimal("3000"), BigDecimal("9000")) shouldBe true
          budgetManager.consumed(promotionId) shouldBe 6000L
      }

      @Test
      fun `reserve exceeding limit fails and does not consume (atomic rollback)`() {
          val promotionId = Random.nextLong(1, 1_000_000_000)
          budgetManager.reserve(promotionId, BigDecimal("8000"), BigDecimal("9000")) shouldBe true
          budgetManager.reserve(promotionId, BigDecimal("3000"), BigDecimal("9000")) shouldBe false
          budgetManager.consumed(promotionId) shouldBe 8000L // 두 번째 예약은 롤백되어 미반영
      }

      @Test
      fun `release returns budget (compensating DECRBY)`() {
          val promotionId = Random.nextLong(1, 1_000_000_000)
          budgetManager.reserve(promotionId, BigDecimal("3000"), BigDecimal("9000")) shouldBe true
          budgetManager.release(promotionId, BigDecimal("3000"))
          budgetManager.consumed(promotionId) shouldBe 0L
      }
  }
  ```

- [ ] **Step 2: 테스트 실행 → 컴파일 실패(빨강)** — Docker 데몬 필요.
  ```
  ./gradlew test --tests "com.commerce.promotion.PromotionBudgetManagerTest"
  ```
  기대: **FAIL** — `Unresolved reference: PromotionBudgetManager`.

- [ ] **Step 3: `PromotionBudgetManager` 작성** — `src/main/kotlin/com/komsco/voucher/promotion/infrastructure/PromotionBudgetManager.kt`. `VoucherIssueService`의 `MONTHLY_LIMIT_CHECK_SCRIPT` 패턴을 그대로 미러(StringCodec + INCRBY/DECRBY):
  ```kotlin
  package com.commerce.promotion.infrastructure

  import org.redisson.api.RScript
  import org.redisson.api.RedissonClient
  import org.redisson.client.codec.StringCodec
  import org.springframework.stereotype.Component
  import java.math.BigDecimal

  /**
   * 프로모션 예산을 Redis 원자 카운터로 제어한다(지역 월 발행 한도 패턴 미러).
   * - reserve: Lua로 INCRBY + 한도 검증을 원자 수행. 초과 시 자동 롤백 후 false.
   * - release: 보상/반환 DECRBY(다운스트림 DB 실패 시, 또는 결제 취소 시 예산 반환).
   * DB 진실원천은 CouponRedemption.discountAmount 합계 → 재동기화 잡이 Redis를 보정.
   */
  @Component
  class PromotionBudgetManager(
      private val redissonClient: RedissonClient,
  ) {

      /** 원자 예약. 예약 후 누적이 한도 이내면 true, 초과면 롤백 후 false. */
      fun reserve(promotionId: Long, discount: BigDecimal, budgetLimit: BigDecimal): Boolean {
          val key = budgetKey(promotionId)
          // StringCodec: ARGV를 평문 문자열로 인코딩해야 Redis INCRBY가 정수로 해석 가능
          val result = redissonClient.getScript(StringCodec.INSTANCE).eval<Long>(
              RScript.Mode.READ_WRITE,
              BUDGET_RESERVE_SCRIPT,
              RScript.ReturnType.INTEGER,
              listOf(key),
              discount.longValueExact().toString(), budgetLimit.longValueExact().toString(),
          )
          return result != -1L
      }

      /** 보상/반환: 소비된 예산을 되돌린다(원자 DECRBY). */
      fun release(promotionId: Long, discount: BigDecimal) {
          redissonClient.getAtomicLong(budgetKey(promotionId)).addAndGet(-discount.longValueExact())
      }

      /** 현재 소비된 예산(원). */
      fun consumed(promotionId: Long): Long =
          redissonClient.getAtomicLong(budgetKey(promotionId)).get()

      private fun budgetKey(promotionId: Long) = "promotion:budget:$promotionId"

      companion object {
          /**
           * KEYS[1]=예산 키, ARGV[1]=증가량(할인액), ARGV[2]=예산 한도.
           * 반환: 성공 시 새 누적, 한도 초과 시 -1(롤백 후).
           */
          private const val BUDGET_RESERVE_SCRIPT = """
              local current = redis.call('INCRBY', KEYS[1], ARGV[1])
              if current > tonumber(ARGV[2]) then
                  redis.call('DECRBY', KEYS[1], ARGV[1])
                  return -1
              end
              return current
          """
      }
  }
  ```

- [ ] **Step 4: 재동기화 스케줄러 작성** — `src/main/kotlin/com/komsco/voucher/promotion/application/PromotionBudgetSyncScheduler.kt`. `RegionCounterSyncScheduler`를 미러(매시 정각, DB 합계로 Redis set):
  ```kotlin
  package com.commerce.promotion.application

  import com.commerce.promotion.infrastructure.CouponRedemptionJpaRepository
  import com.commerce.promotion.infrastructure.PromotionJpaRepository
  import org.redisson.api.RedissonClient
  import org.slf4j.LoggerFactory
  import org.springframework.scheduling.annotation.Scheduled
  import org.springframework.stereotype.Component

  /**
   * Redis 프로모션 예산 카운터를 DB(CouponRedemption 합계) 기준으로 재동기화.
   * Redis 재시작/보상 누락으로 카운터가 어긋나도 매시 정각에 DB 진실원천으로 복구한다.
   * (RegionCounterSyncScheduler와 동형: voucher 합계 → region 카운터 보정 패턴 미러)
   */
  @Component
  class PromotionBudgetSyncScheduler(
      private val promotionRepository: PromotionJpaRepository,
      private val couponRedemptionRepository: CouponRedemptionJpaRepository,
      private val redissonClient: RedissonClient,
  ) {
      private val log = LoggerFactory.getLogger(javaClass)

      @Scheduled(cron = "0 0 * * * *")
      fun syncPromotionBudgets() {
          promotionRepository.findAll().forEach { promotion ->
              try {
                  val key = "promotion:budget:${promotion.id}"
                  val dbConsumed = couponRedemptionRepository.sumActiveDiscountByPromotion(promotion.id)
                  redissonClient.getAtomicLong(key).set(dbConsumed.toLong())
                  log.debug("Promotion {} budget synced: {}", promotion.id, dbConsumed)
              } catch (e: Exception) {
                  log.error("Failed to sync promotion {} budget: {}", promotion.id, e.message)
              }
          }
      }
  }
  ```

- [ ] **Step 5: 테스트 재실행 → 통과(초록)** — Docker 데몬 필요.
  ```
  ./gradlew test --tests "com.commerce.promotion.PromotionBudgetManagerTest"
  ```
  기대: **PASS** — 3개 테스트 통과(예약 누적, 한도 초과 롤백, 반환).

- [ ] **Step 6: 커밋**
  ```
  git add src/main/kotlin/com/komsco/voucher/promotion/infrastructure/PromotionBudgetManager.kt \
          src/main/kotlin/com/komsco/voucher/promotion/application/PromotionBudgetSyncScheduler.kt \
          src/test/kotlin/com/komsco/voucher/promotion/PromotionBudgetManagerTest.kt
  git commit -m "feat(promotion): Redis Lua 예산 원자 예약/보상 매니저 + 재동기화 스케줄러"
  ```

---

### Task 4: 프로모션/쿠폰 서비스 + DTO + 컨트롤러(멱등 발급·회원 쿠폰 조회) (스펙 §4.1 API)

**Files:**
- Create: `src/main/kotlin/com/komsco/voucher/promotion/application/PromotionService.kt`
- Create: `src/main/kotlin/com/komsco/voucher/promotion/application/CouponIssueService.kt`
- Create: `src/main/kotlin/com/komsco/voucher/promotion/interfaces/dto/PromotionDtos.kt`
- Create: `src/main/kotlin/com/komsco/voucher/promotion/interfaces/PromotionController.kt`
- Create: `src/main/kotlin/com/komsco/voucher/promotion/interfaces/MemberCouponController.kt`
- Create: `src/test/kotlin/com/komsco/voucher/promotion/CouponIssueServiceTest.kt`

**Interfaces:**
- Consumes: `PromotionJpaRepository`, `CouponJpaRepository`, `Promotion`/`Coupon`/`PromotionStatus`/`CouponStatus`/`DiscountType`, `TransactionTemplate`, `@Idempotent`(common/idempotency), `SecurityUtils.currentMemberId()`(Plan 1), `BusinessException`.
- Produces:
  - `class PromotionService` — `create(request: CreatePromotionRequest): Promotion`(상태 `ACTIVE`로 생성), `getById(id: Long): Promotion`.
  - `class CouponIssueService` — `issue(promotionId: Long, memberId: Long): Coupon`, `findByMember(memberId: Long): List<Coupon>`.
  - DTO: `CreatePromotionRequest`, `PromotionResponse`, `CouponResponse`.
  - `POST /api/v1/promotions` → `PromotionResponse`(201).
  - `GET /api/v1/promotions/{id}` → `PromotionResponse`.
  - `POST /api/v1/promotions/{id}/coupons` (`@Idempotent`) → `CouponResponse`(201), 신원=`currentMemberId()`.
  - `GET /api/v1/members/{memberId}/coupons` → `List<CouponResponse>`(본인만).

- [ ] **Step 1: 실패 통합 테스트 작성** — `src/test/kotlin/com/komsco/voucher/promotion/CouponIssueServiceTest.kt`. 서비스 레벨(인증 불필요):
  ```kotlin
  package com.commerce.promotion

  import com.commerce.common.exception.BusinessException
  import com.commerce.common.exception.ErrorCode
  import com.commerce.promotion.application.CouponIssueService
  import com.commerce.promotion.application.PromotionService
  import com.commerce.promotion.domain.CouponStatus
  import com.commerce.promotion.domain.DiscountType
  import com.commerce.promotion.interfaces.dto.CreatePromotionRequest
  import com.commerce.support.IntegrationTestSupport
  import io.kotest.assertions.throwables.shouldThrow
  import io.kotest.matchers.shouldBe
  import org.junit.jupiter.api.Test
  import org.springframework.beans.factory.annotation.Autowired
  import java.math.BigDecimal
  import java.time.LocalDateTime

  class CouponIssueServiceTest : IntegrationTestSupport() {

      @Autowired lateinit var promotionService: PromotionService
      @Autowired lateinit var couponIssueService: CouponIssueService

      private fun createPromotionRequest() = CreatePromotionRequest(
          name = "발급 테스트",
          discountType = DiscountType.FIXED,
          discountValue = BigDecimal("3000"),
          minSpend = BigDecimal.ZERO,
          perMemberLimit = 1,
          budgetLimit = BigDecimal("1000000"),
          startsAt = LocalDateTime.now().minusDays(1),
          endsAt = LocalDateTime.now().plusDays(7),
      )

      @Test
      fun `issue creates an ISSUED coupon owned by the member with promotion expiry`() {
          val promotion = promotionService.create(createPromotionRequest())
          val coupon = couponIssueService.issue(promotion.id, memberId = 42L)

          coupon.status shouldBe CouponStatus.ISSUED
          coupon.memberId shouldBe 42L
          coupon.promotionId shouldBe promotion.id
          coupon.expiresAt shouldBe promotion.endsAt
          couponIssueService.findByMember(42L).map { it.id } shouldBe listOf(coupon.id)
      }

      @Test
      fun `issue on an inactive promotion is rejected`() {
          val promotion = promotionService.create(
              createPromotionRequest().copy(
                  startsAt = LocalDateTime.now().minusDays(10),
                  endsAt = LocalDateTime.now().minusDays(1), // 이미 종료
              )
          )
          shouldThrow<BusinessException> { couponIssueService.issue(promotion.id, 42L) }
              .errorCode shouldBe ErrorCode.PROMOTION_NOT_ACTIVE
      }
  }
  ```

- [ ] **Step 2: 테스트 실행 → 컴파일 실패(빨강)** — Docker 데몬 필요.
  ```
  ./gradlew test --tests "com.commerce.promotion.CouponIssueServiceTest"
  ```
  기대: **FAIL** — `Unresolved reference: PromotionService / CouponIssueService / CreatePromotionRequest`.

- [ ] **Step 3: DTO 작성** — `src/main/kotlin/com/komsco/voucher/promotion/interfaces/dto/PromotionDtos.kt`:
  ```kotlin
  package com.commerce.promotion.interfaces.dto

  import com.commerce.promotion.domain.Coupon
  import com.commerce.promotion.domain.DiscountType
  import com.commerce.promotion.domain.Promotion
  import jakarta.validation.constraints.Min
  import jakarta.validation.constraints.NotBlank
  import jakarta.validation.constraints.NotNull
  import java.math.BigDecimal
  import java.time.LocalDateTime

  data class CreatePromotionRequest(
      @field:NotBlank val name: String,
      @field:NotNull val discountType: DiscountType,
      @field:NotNull val discountValue: BigDecimal,
      @field:NotNull val minSpend: BigDecimal,
      @field:NotNull @field:Min(1) val perMemberLimit: Int,
      @field:NotNull val budgetLimit: BigDecimal,
      @field:NotNull val startsAt: LocalDateTime,
      @field:NotNull val endsAt: LocalDateTime,
  )

  data class PromotionResponse(
      val id: Long,
      val name: String,
      val discountType: DiscountType,
      val discountValue: BigDecimal,
      val minSpend: BigDecimal,
      val perMemberLimit: Int,
      val budgetLimit: BigDecimal,
      val status: String,
      val startsAt: LocalDateTime,
      val endsAt: LocalDateTime,
  ) {
      companion object {
          fun from(p: Promotion) = PromotionResponse(
              id = p.id, name = p.name, discountType = p.discountType, discountValue = p.discountValue,
              minSpend = p.minSpend, perMemberLimit = p.perMemberLimit, budgetLimit = p.budgetLimit,
              status = p.status.name, startsAt = p.startsAt, endsAt = p.endsAt,
          )
      }
  }

  data class CouponResponse(
      val id: Long,
      val promotionId: Long,
      val memberId: Long,
      val status: String,
      val expiresAt: LocalDateTime,
  ) {
      companion object {
          fun from(c: Coupon) = CouponResponse(c.id, c.promotionId, c.memberId, c.status.name, c.expiresAt)
      }
  }
  ```

- [ ] **Step 4: `PromotionService` 작성** — `src/main/kotlin/com/komsco/voucher/promotion/application/PromotionService.kt`. 생성 시 `ACTIVE`(관리자 직접 생성; AI 초안 흐름의 `DRAFT→activate()`는 Plan 4):
  ```kotlin
  package com.commerce.promotion.application

  import com.commerce.common.exception.BusinessException
  import com.commerce.common.exception.ErrorCode
  import com.commerce.promotion.domain.Promotion
  import com.commerce.promotion.domain.PromotionStatus
  import com.commerce.promotion.infrastructure.PromotionJpaRepository
  import com.commerce.promotion.interfaces.dto.CreatePromotionRequest
  import org.springframework.stereotype.Service
  import org.springframework.transaction.annotation.Transactional

  @Service
  @Transactional(readOnly = true)
  class PromotionService(
      private val promotionRepository: PromotionJpaRepository,
  ) {

      @Transactional
      fun create(request: CreatePromotionRequest): Promotion =
          promotionRepository.save(
              Promotion(
                  name = request.name,
                  discountType = request.discountType,
                  discountValue = request.discountValue,
                  minSpend = request.minSpend,
                  perMemberLimit = request.perMemberLimit,
                  budgetLimit = request.budgetLimit,
                  startsAt = request.startsAt,
                  endsAt = request.endsAt,
                  status = PromotionStatus.ACTIVE,
              )
          )

      fun getById(id: Long): Promotion =
          promotionRepository.findById(id)
              .orElseThrow { BusinessException(ErrorCode.ENTITY_NOT_FOUND) }
  }
  ```

- [ ] **Step 5: `CouponIssueService` 작성** — `src/main/kotlin/com/komsco/voucher/promotion/application/CouponIssueService.kt`. 멱등 발급의 1차 방어는 컨트롤러 `@Idempotent`이고, 서비스는 활성 프로모션에서만 발급:
  ```kotlin
  package com.commerce.promotion.application

  import com.commerce.common.exception.BusinessException
  import com.commerce.common.exception.ErrorCode
  import com.commerce.promotion.domain.Coupon
  import com.commerce.promotion.infrastructure.CouponJpaRepository
  import com.commerce.promotion.infrastructure.PromotionJpaRepository
  import org.springframework.stereotype.Service
  import org.springframework.transaction.support.TransactionTemplate

  @Service
  class CouponIssueService(
      private val promotionRepository: PromotionJpaRepository,
      private val couponRepository: CouponJpaRepository,
      private val transactionTemplate: TransactionTemplate,
  ) {

      fun issue(promotionId: Long, memberId: Long): Coupon =
          transactionTemplate.execute { _ ->
              val promotion = promotionRepository.findById(promotionId)
                  .orElseThrow { BusinessException(ErrorCode.ENTITY_NOT_FOUND) }
              if (!promotion.isActive()) throw BusinessException(ErrorCode.PROMOTION_NOT_ACTIVE)
              couponRepository.save(
                  Coupon(promotionId = promotionId, memberId = memberId, expiresAt = promotion.endsAt)
              )
          }!!

      fun findByMember(memberId: Long): List<Coupon> = couponRepository.findByMemberId(memberId)
  }
  ```

- [ ] **Step 6: `PromotionController` 작성** — `src/main/kotlin/com/komsco/voucher/promotion/interfaces/PromotionController.kt`. 쿠폰 발급은 `@Idempotent` + 신원은 `SecurityUtils.currentMemberId()`(body 미신뢰):
  ```kotlin
  package com.commerce.promotion.interfaces

  import com.commerce.common.idempotency.Idempotent
  import com.commerce.common.security.SecurityUtils
  import com.commerce.promotion.application.CouponIssueService
  import com.commerce.promotion.application.PromotionService
  import com.commerce.promotion.interfaces.dto.CouponResponse
  import com.commerce.promotion.interfaces.dto.CreatePromotionRequest
  import com.commerce.promotion.interfaces.dto.PromotionResponse
  import jakarta.validation.Valid
  import org.springframework.http.HttpStatus
  import org.springframework.web.bind.annotation.*

  @RestController
  @RequestMapping("/api/v1/promotions")
  class PromotionController(
      private val promotionService: PromotionService,
      private val couponIssueService: CouponIssueService,
  ) {

      @PostMapping
      @ResponseStatus(HttpStatus.CREATED)
      fun create(@Valid @RequestBody request: CreatePromotionRequest): PromotionResponse =
          PromotionResponse.from(promotionService.create(request))

      @GetMapping("/{id}")
      fun getById(@PathVariable id: Long): PromotionResponse =
          PromotionResponse.from(promotionService.getById(id))

      @PostMapping("/{id}/coupons")
      @ResponseStatus(HttpStatus.CREATED)
      @Idempotent
      fun issueCoupon(@PathVariable id: Long): CouponResponse =
          CouponResponse.from(couponIssueService.issue(id, SecurityUtils.currentMemberId()))
  }
  ```

- [ ] **Step 7: `MemberCouponController` 작성** — `src/main/kotlin/com/komsco/voucher/promotion/interfaces/MemberCouponController.kt`. 본인 쿠폰만 조회(path memberId가 principal과 다르면 거부):
  ```kotlin
  package com.commerce.promotion.interfaces

  import com.commerce.common.exception.BusinessException
  import com.commerce.common.exception.ErrorCode
  import com.commerce.common.security.SecurityUtils
  import com.commerce.promotion.application.CouponIssueService
  import com.commerce.promotion.interfaces.dto.CouponResponse
  import org.springframework.web.bind.annotation.GetMapping
  import org.springframework.web.bind.annotation.PathVariable
  import org.springframework.web.bind.annotation.RequestMapping
  import org.springframework.web.bind.annotation.RestController

  @RestController
  @RequestMapping("/api/v1/members")
  class MemberCouponController(
      private val couponIssueService: CouponIssueService,
  ) {

      @GetMapping("/{memberId}/coupons")
      fun listCoupons(@PathVariable memberId: Long): List<CouponResponse> {
          if (memberId != SecurityUtils.currentMemberId())
              throw BusinessException(ErrorCode.INVALID_INPUT, "본인의 쿠폰만 조회할 수 있습니다")
          return couponIssueService.findByMember(memberId).map { CouponResponse.from(it) }
      }
  }
  ```

- [ ] **Step 8: 테스트 재실행 → 통과(초록)** — Docker 데몬 필요.
  ```
  ./gradlew test --tests "com.commerce.promotion.CouponIssueServiceTest"
  ```
  기대: **PASS** — 발급 성공 + 비활성 프로모션 거부.

- [ ] **Step 9: 커밋**
  ```
  git add src/main/kotlin/com/komsco/voucher/promotion/application/PromotionService.kt \
          src/main/kotlin/com/komsco/voucher/promotion/application/CouponIssueService.kt \
          src/main/kotlin/com/komsco/voucher/promotion/interfaces/dto/PromotionDtos.kt \
          src/main/kotlin/com/komsco/voucher/promotion/interfaces/PromotionController.kt \
          src/main/kotlin/com/komsco/voucher/promotion/interfaces/MemberCouponController.kt \
          src/test/kotlin/com/komsco/voucher/promotion/CouponIssueServiceTest.kt
  git commit -m "feat(promotion): 프로모션 생성/조회 + 멱등 쿠폰 발급 + 회원 쿠폰 조회 API"
  ```

---

### Task 5: 결합 결제 오케스트레이터 + redeem 엔드포인트 확장 + E2E/T-account 회귀 (스펙 §3.2, §4.1, §4.3)

**Files:**
- Modify: `src/main/kotlin/com/komsco/voucher/voucher/infrastructure/VoucherLockManager.kt` (현재 20행 `withMemberPurchaseLock` 다음에 `withCouponLock` 추가)
- Create: `src/main/kotlin/com/komsco/voucher/promotion/application/RedemptionOrchestrator.kt`
- Modify: `src/main/kotlin/com/komsco/voucher/voucher/interfaces/dto/VoucherRequest.kt` (13~16행 `RedeemRequest`에 `couponId` 추가)
- Modify: `src/main/kotlin/com/komsco/voucher/voucher/interfaces/VoucherController.kt` (23~30행 생성자, 55~58행 redeem 메서드 — 오케스트레이터로 위임)
- Modify: `src/test/kotlin/com/komsco/voucher/support/TestFixtures.kt` (생성자 + import + 헬퍼 `createPromotion`/`issueCoupon` 추가)
- Create: `src/test/kotlin/com/komsco/voucher/integration/CouponRedeemIntegrationTest.kt`

**Interfaces:**
- Consumes (locked contract): `LedgerService.record(...)` / `getEntriesByTransactionId(...)` / `netBalanceByAccount(...)`, `AccountCode.MERCHANT_RECEIVABLE/VOUCHER_BALANCE/PROMOTION_FUNDING`, `LedgerEntryType.REDEMPTION/COUPON_SUBSIDY`, `TransactionService.create(...)` + `tx.complete()`, `VoucherJpaRepository.findByIdForUpdate(id)`, `VoucherLockManager`, `VoucherRedemptionService.redeem(...)`, `RedemptionResult(transactionId, remainingBalance)`, `PromotionBudgetManager`, `CouponJpaRepository`/`PromotionJpaRepository`/`CouponRedemptionJpaRepository`.
- Produces:
  - `VoucherLockManager.withCouponLock(couponId: Long, action: () -> T): T`.
  - `class RedemptionOrchestrator` — `redeem(voucherId: Long, merchantId: Long, orderTotal: BigDecimal, couponId: Long?): RedemptionResult`.
  - `RedeemRequest.couponId: Long?`(기본 null).
  - `TestFixtures.createPromotion(...) : Promotion`, `TestFixtures.issueCoupon(promotionId, memberId): Coupon`.

- [ ] **Step 1: 실패 E2E/T-account 통합 테스트 작성** — `src/test/kotlin/com/komsco/voucher/integration/CouponRedeemIntegrationTest.kt`. (TestFixtures 헬퍼는 Step 4에서 추가; 이 테스트는 그 헬퍼를 사용하므로 Step 5 컴파일까지 빨강.)
  ```kotlin
  package com.commerce.integration

  import com.commerce.ledger.application.LedgerService
  import com.commerce.ledger.application.LedgerVerificationService
  import com.commerce.ledger.domain.AccountCode
  import com.commerce.ledger.domain.LedgerEntrySide
  import com.commerce.promotion.application.RedemptionOrchestrator
  import com.commerce.promotion.domain.CouponStatus
  import com.commerce.promotion.domain.DiscountType
  import com.commerce.promotion.infrastructure.CouponJpaRepository
  import com.commerce.promotion.infrastructure.CouponRedemptionJpaRepository
  import com.commerce.support.IntegrationTestSupport
  import com.commerce.support.TestFixtures
  import com.commerce.transaction.application.TransactionService
  import com.commerce.voucher.infrastructure.VoucherJpaRepository
  import io.kotest.matchers.shouldBe
  import org.junit.jupiter.api.BeforeEach
  import org.junit.jupiter.api.Test
  import org.springframework.beans.factory.annotation.Autowired
  import java.math.BigDecimal
  import java.util.UUID

  class CouponRedeemIntegrationTest : IntegrationTestSupport() {

      @Autowired lateinit var fixtures: TestFixtures
      @Autowired lateinit var orchestrator: RedemptionOrchestrator
      @Autowired lateinit var voucherRepository: VoucherJpaRepository
      @Autowired lateinit var couponRepository: CouponJpaRepository
      @Autowired lateinit var couponRedemptionRepository: CouponRedemptionJpaRepository
      @Autowired lateinit var transactionService: TransactionService
      @Autowired lateinit var ledgerService: LedgerService
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
      fun `coupon-applied redeem charges T-D to voucher and posts two balanced ledger pairs`() {
          val voucher = fixtures.issueVoucher(memberId, regionId, BigDecimal("50000"))
          val promotion = fixtures.createPromotion(
              discountType = DiscountType.FIXED, discountValue = BigDecimal("3000"),
              budgetLimit = BigDecimal("1000000"),
          )
          val coupon = fixtures.issueCoupon(promotion.id, memberId)

          // 주문총액 T=10,000, 할인 D=3,000, 바우처 차감 T-D=7,000
          val result = orchestrator.redeem(voucher.id, merchantId, BigDecimal("10000"), coupon.id)

          // 바우처 잔액 = 50,000 - 7,000 = 43,000
          voucherRepository.findById(voucher.id).get().balance.compareTo(BigDecimal("43000")) shouldBe 0

          // transaction.amount = gross T = 10,000
          transactionService.getById(result.transactionId).amount.compareTo(BigDecimal("10000")) shouldBe 0

          // 쿠폰 REDEEMED + CouponRedemption 기록(D=3,000, charged=7,000)
          couponRepository.findById(coupon.id).get().status shouldBe CouponStatus.REDEEMED
          val cr = couponRedemptionRepository.findByTransactionId(result.transactionId)!!
          cr.discountAmount.compareTo(BigDecimal("3000")) shouldBe 0
          cr.voucherCharged.compareTo(BigDecimal("7000")) shouldBe 0

          // T-account: 같은 txId에 4개 leg(쌍1 REDEMPTION + 쌍2 COUPON_SUBSIDY)
          val entries = ledgerService.getEntriesByTransactionId(result.transactionId)
          entries.size shouldBe 4
          val mrDebit = entries.filter { it.account == AccountCode.MERCHANT_RECEIVABLE && it.side == LedgerEntrySide.DEBIT }
              .fold(BigDecimal.ZERO) { acc, e -> acc + e.amount }
          mrDebit.compareTo(BigDecimal("10000")) shouldBe 0
          val vbCredit = entries.single { it.account == AccountCode.VOUCHER_BALANCE && it.side == LedgerEntrySide.CREDIT }
          vbCredit.amount.compareTo(BigDecimal("7000")) shouldBe 0
          val pfCredit = entries.single { it.account == AccountCode.PROMOTION_FUNDING && it.side == LedgerEntrySide.CREDIT }
          pfCredit.amount.compareTo(BigDecimal("3000")) shouldBe 0
          val debitSum = entries.filter { it.side == LedgerEntrySide.DEBIT }.fold(BigDecimal.ZERO) { a, e -> a + e.amount }
          val creditSum = entries.filter { it.side == LedgerEntrySide.CREDIT }.fold(BigDecimal.ZERO) { a, e -> a + e.amount }
          debitSum.compareTo(creditSum) shouldBe 0

          // 글로벌 정합성 통과
          verificationService.verify().isBalanced shouldBe true
      }

      @Test
      fun `discount is clamped to order total (no over-discount, no negative voucher charge)`() {
          val voucher = fixtures.issueVoucher(memberId, regionId, BigDecimal("50000"))
          val promotion = fixtures.createPromotion(
              discountType = DiscountType.FIXED, discountValue = BigDecimal("3000"),
              budgetLimit = BigDecimal("1000000"),
          )
          val coupon = fixtures.issueCoupon(promotion.id, memberId)

          // 주문 2,000 < 정액 3,000 → D는 2,000으로 클램프, 바우처 차감 0
          val result = orchestrator.redeem(voucher.id, merchantId, BigDecimal("2000"), coupon.id)

          val cr = couponRedemptionRepository.findByTransactionId(result.transactionId)!!
          cr.discountAmount.compareTo(BigDecimal("2000")) shouldBe 0
          cr.voucherCharged.compareTo(BigDecimal.ZERO) shouldBe 0
          voucherRepository.findById(voucher.id).get().balance.compareTo(BigDecimal("50000")) shouldBe 0
          verificationService.verify().isBalanced shouldBe true
      }

      @Test
      fun `redeem below min-spend is rejected and consumes no budget`() {
          val voucher = fixtures.issueVoucher(memberId, regionId, BigDecimal("50000"))
          val promotion = fixtures.createPromotion(
              discountType = DiscountType.FIXED, discountValue = BigDecimal("3000"),
              minSpend = BigDecimal("20000"), budgetLimit = BigDecimal("1000000"),
          )
          val coupon = fixtures.issueCoupon(promotion.id, memberId)

          try {
              orchestrator.redeem(voucher.id, merchantId, BigDecimal("10000"), coupon.id)
          } catch (e: com.commerce.common.exception.BusinessException) {
              e.errorCode shouldBe com.commerce.common.exception.ErrorCode.MIN_SPEND_NOT_MET
          }
          // 바우처/쿠폰 불변 + 정합성 유지
          voucherRepository.findById(voucher.id).get().balance.compareTo(BigDecimal("50000")) shouldBe 0
          couponRepository.findById(coupon.id).get().status shouldBe CouponStatus.ISSUED
          verificationService.verify().isBalanced shouldBe true
      }
  }
  ```

- [ ] **Step 2: 테스트 실행 → 컴파일 실패(빨강)** — Docker 데몬 필요.
  ```
  ./gradlew test --tests "com.commerce.integration.CouponRedeemIntegrationTest"
  ```
  기대: **FAIL** — `Unresolved reference: RedemptionOrchestrator / createPromotion / issueCoupon`.

- [ ] **Step 3: `withCouponLock` 추가** — `src/main/kotlin/com/komsco/voucher/voucher/infrastructure/VoucherLockManager.kt`의 `withMemberPurchaseLock`(20~21행) 다음에 메서드를 추가:
  ```kotlin
      fun <T> withCouponLock(couponId: Long, action: () -> T): T =
          withLock("coupon:$couponId", action)
  ```
  (정준 락 순서: `coupon → voucher`. 키 접두사 `coupon`은 `substringBefore(':')`로 메트릭 태그가 자동 분리됨.)

- [ ] **Step 4: `RedemptionOrchestrator` 작성** — `src/main/kotlin/com/komsco/voucher/promotion/application/RedemptionOrchestrator.kt`. 쿠폰 없으면 기존 서비스 위임, 있으면 `coupon→voucher` 락 + 예산 예약(보상 DECRBY) + txId 공유 2쌍 분개:
  ```kotlin
  package com.commerce.promotion.application

  import com.commerce.common.exception.BusinessException
  import com.commerce.common.exception.ErrorCode
  import com.commerce.ledger.application.LedgerService
  import com.commerce.ledger.domain.AccountCode
  import com.commerce.ledger.domain.LedgerEntryType
  import com.commerce.promotion.domain.CouponRedemption
  import com.commerce.promotion.domain.CouponStatus
  import com.commerce.promotion.infrastructure.CouponJpaRepository
  import com.commerce.promotion.infrastructure.CouponRedemptionJpaRepository
  import com.commerce.promotion.infrastructure.PromotionBudgetManager
  import com.commerce.promotion.infrastructure.PromotionJpaRepository
  import com.commerce.transaction.application.TransactionService
  import com.commerce.transaction.domain.TransactionType
  import com.commerce.voucher.application.VoucherRedemptionService
  import com.commerce.voucher.infrastructure.VoucherJpaRepository
  import com.commerce.voucher.infrastructure.VoucherLockManager
  import com.commerce.voucher.interfaces.dto.RedemptionResult
  import io.micrometer.core.instrument.MeterRegistry
  import io.micrometer.core.instrument.Timer
  import org.springframework.stereotype.Service
  import org.springframework.transaction.support.TransactionTemplate
  import java.math.BigDecimal

  /**
   * 결합 결제(바우처 + 쿠폰) 오케스트레이터.
   * 정준 락 순서 coupon → voucher 로 데드락을 방지하고,
   * 예산 예약은 DB tx 밖에서 원자 수행한 뒤 다운스트림 실패 시 보상 DECRBY 한다.
   * 분개는 동일 transactionId를 공유하는 균형 2-leg 2쌍(바우처분 REDEMPTION + 보조분 COUPON_SUBSIDY).
   */
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
      private val transactionTemplate: TransactionTemplate,
  ) {

      /**
       * @param orderTotal 주문 총액 T (= 가맹점 수취 gross). 쿠폰 없으면 바우처 차감액과 동일.
       * @param couponId 적용할 단일 쿠폰(없으면 일반 바우처 결제로 위임).
       */
      fun redeem(voucherId: Long, merchantId: Long, orderTotal: BigDecimal, couponId: Long?): RedemptionResult {
          if (couponId == null) {
              return redemptionService.redeem(voucherId, merchantId, orderTotal)
          }
          return lockManager.withCouponLock(couponId) {
              lockManager.withVoucherLock(voucherId) {
                  redeemWithCoupon(voucherId, merchantId, orderTotal, couponId)
              }
          }
      }

      private fun redeemWithCoupon(
          voucherId: Long,
          merchantId: Long,
          orderTotal: BigDecimal,
          couponId: Long,
      ): RedemptionResult {
          // 1) 사전 검증(읽기) + 할인액 산정 — 예산 예약 전에 빠르게 거부
          val coupon = couponRepository.findById(couponId)
              .orElseThrow { BusinessException(ErrorCode.COUPON_NOT_FOUND) }
          if (coupon.status != CouponStatus.ISSUED) throw BusinessException(ErrorCode.COUPON_ALREADY_USED)
          if (coupon.isExpired()) throw BusinessException(ErrorCode.COUPON_EXPIRED)

          val promotion = promotionRepository.findById(coupon.promotionId)
              .orElseThrow { BusinessException(ErrorCode.ENTITY_NOT_FOUND) }
          if (!promotion.isActive()) throw BusinessException(ErrorCode.PROMOTION_NOT_ACTIVE)
          if (orderTotal < promotion.minSpend) throw BusinessException(ErrorCode.MIN_SPEND_NOT_MET)

          val usedCount = couponRedemptionRepository
              .countByMemberIdAndPromotionIdAndCancelledFalse(coupon.memberId, promotion.id)
          if (usedCount >= promotion.perMemberLimit) throw BusinessException(ErrorCode.COUPON_USAGE_LIMIT_EXCEEDED)

          val rawDiscount = promotion.calculateDiscount(orderTotal)
          val discount = rawDiscount.min(orderTotal) // 과할인 클램프 D = min(D, T)
          if (discount <= BigDecimal.ZERO) throw BusinessException(ErrorCode.INVALID_DISCOUNT)
          val voucherCharged = orderTotal - discount

          // 2) 예산 원자 예약 — DB tx 밖. 초과 시 즉시 거부, 실패 시 finally에서 보상 DECRBY
          if (!budgetManager.reserve(promotion.id, discount, promotion.budgetLimit)) {
              throw BusinessException(ErrorCode.PROMOTION_BUDGET_EXCEEDED)
          }

          val timer = Timer.start(meterRegistry)
          var committed = false
          try {
              val result = transactionTemplate.execute { _ ->
                  // 쿠폰 락이 직렬화하므로 재조회로 이중 사용 방지(상태 ISSUED 재확인)
                  val lockedCoupon = couponRepository.findById(couponId)
                      .orElseThrow { BusinessException(ErrorCode.COUPON_NOT_FOUND) }
                  if (lockedCoupon.status != CouponStatus.ISSUED)
                      throw BusinessException(ErrorCode.COUPON_ALREADY_USED)

                  val voucher = voucherRepository.findByIdForUpdate(voucherId)
                      ?: throw BusinessException(ErrorCode.ENTITY_NOT_FOUND)
                  if (voucher.memberId != lockedCoupon.memberId)
                      throw BusinessException(ErrorCode.INVALID_INPUT, "쿠폰 소유자의 상품권이 아닙니다")
                  if (!voucher.isUsable()) throw BusinessException(ErrorCode.VOUCHER_NOT_USABLE)
                  if (voucher.isExpired()) throw BusinessException(ErrorCode.VOUCHER_EXPIRED)
                  if (voucher.balance < voucherCharged) throw BusinessException(ErrorCode.INSUFFICIENT_BALANCE)

                  voucher.redeem(voucherCharged)

                  val tx = transactionService.create(
                      type = TransactionType.REDEMPTION,
                      amount = orderTotal,                 // gross T (정산 집계 기준)
                      voucherId = voucherId,
                      merchantId = merchantId,
                      memberId = lockedCoupon.memberId,
                  )

                  // 쌍1: 바우처 결제분 (DEBIT MERCHANT_RECEIVABLE / CREDIT VOUCHER_BALANCE, T-D)
                  ledgerService.record(
                      debitAccount = AccountCode.MERCHANT_RECEIVABLE,
                      creditAccount = AccountCode.VOUCHER_BALANCE,
                      amount = voucherCharged,
                      transactionId = tx.id,
                      entryType = LedgerEntryType.REDEMPTION,
                  )
                  // 쌍2: 플랫폼 보조분 (DEBIT MERCHANT_RECEIVABLE / CREDIT PROMOTION_FUNDING, D)
                  ledgerService.record(
                      debitAccount = AccountCode.MERCHANT_RECEIVABLE,
                      creditAccount = AccountCode.PROMOTION_FUNDING,
                      amount = discount,
                      transactionId = tx.id,
                      entryType = LedgerEntryType.COUPON_SUBSIDY,
                  )

                  lockedCoupon.redeem()
                  couponRedemptionRepository.save(
                      CouponRedemption(
                          couponId = couponId,
                          promotionId = promotion.id,
                          memberId = lockedCoupon.memberId,
                          voucherId = voucherId,
                          transactionId = tx.id,
                          orderTotal = orderTotal,
                          discountAmount = discount,
                          voucherCharged = voucherCharged,
                      )
                  )
                  tx.complete()

                  RedemptionResult(transactionId = tx.id, remainingBalance = voucher.balance)
              }!!
              committed = true
              meterRegistry.counter("coupon.redemption.count", "result", "success").increment()
              result
          } catch (e: Exception) {
              meterRegistry.counter("coupon.redemption.count", "result", "failure").increment()
              throw e
          } finally {
              // DB tx 실패(롤백) 시 예약된 예산을 보상 반환(예산 누수 방지)
              if (!committed) budgetManager.release(promotion.id, discount)
              timer.stop(meterRegistry.timer("coupon.redemption.duration"))
          }
      }
  }
  ```

- [ ] **Step 5: `RedeemRequest`에 `couponId` 추가** — `src/main/kotlin/com/komsco/voucher/voucher/interfaces/dto/VoucherRequest.kt`의 `RedeemRequest`(13~16행)를 다음으로 교체:
  ```kotlin
  data class RedeemRequest(
      @field:NotNull val merchantId: Long,
      @field:NotNull @field:Min(1) val amount: BigDecimal, // 주문 총액 T (gross)
      val couponId: Long? = null,
  )
  ```

- [ ] **Step 6: `VoucherController.redeem`를 오케스트레이터로 위임** — `src/main/kotlin/com/komsco/voucher/voucher/interfaces/VoucherController.kt`. (1) 생성자에서 `redemptionService` 제거하고 `redemptionOrchestrator` 주입, (2) redeem 본문 교체. 생성자(23~30행)를 다음으로:
  ```kotlin
  class VoucherController(
      private val issueService: VoucherIssueService,
      private val redemptionOrchestrator: com.commerce.promotion.application.RedemptionOrchestrator,
      private val refundService: VoucherRefundService,
      private val withdrawalService: VoucherWithdrawalService,
      private val voucherQueryRepository: VoucherQueryRepository,
      private val voucherJpaRepository: VoucherJpaRepository,
  ) {
  ```
  redeem 메서드(55~58행)를 다음으로:
  ```kotlin
      @PostMapping("/{id}/redeem")
      @Idempotent
      fun redeem(@PathVariable id: Long, @Valid @RequestBody request: RedeemRequest): RedemptionResult =
          redemptionOrchestrator.redeem(id, request.merchantId, request.amount, request.couponId)
  ```
  (`import com.commerce.voucher.application.VoucherRedemptionService`는 더 이상 사용하지 않으면 제거. 위 생성자에서 FQCN을 직접 사용하므로 별도 import 불필요.)

- [ ] **Step 7: `TestFixtures`에 헬퍼 추가** — `src/test/kotlin/com/komsco/voucher/support/TestFixtures.kt`. (1) import 추가, (2) 생성자에 `PromotionService`/`CouponIssueService` 주입, (3) `issueVoucher` 다음에 헬퍼 추가.
  - import 블록(13~17행 부근)에 추가:
    ```kotlin
    import com.commerce.promotion.application.CouponIssueService
    import com.commerce.promotion.application.PromotionService
    import com.commerce.promotion.domain.Coupon
    import com.commerce.promotion.domain.DiscountType
    import com.commerce.promotion.domain.Promotion
    import com.commerce.promotion.interfaces.dto.CreatePromotionRequest
    ```
  - 생성자(21~27행)를 다음으로 교체:
    ```kotlin
    class TestFixtures(
        private val regionService: RegionService,
        private val memberService: MemberService,
        private val merchantService: MerchantService,
        private val voucherIssueService: VoucherIssueService,
        private val voucherJpaRepository: VoucherJpaRepository,
        private val promotionService: PromotionService,
        private val couponIssueService: CouponIssueService,
    ) {
    ```
  - `issueVoucher(...)`(78~84행) 다음에 헬퍼 추가:
    ```kotlin
    fun createPromotion(
        discountType: DiscountType = DiscountType.FIXED,
        discountValue: BigDecimal = BigDecimal("3000"),
        minSpend: BigDecimal = BigDecimal.ZERO,
        perMemberLimit: Int = 1,
        budgetLimit: BigDecimal = BigDecimal("1000000"),
    ): Promotion = promotionService.create(
        CreatePromotionRequest(
            name = "프로모션${counter++}",
            discountType = discountType,
            discountValue = discountValue,
            minSpend = minSpend,
            perMemberLimit = perMemberLimit,
            budgetLimit = budgetLimit,
            startsAt = LocalDateTime.now().minusDays(1),
            endsAt = LocalDateTime.now().plusDays(30),
        )
    )

    fun issueCoupon(promotionId: Long, memberId: Long): Coupon =
        couponIssueService.issue(promotionId, memberId)
    ```

- [ ] **Step 8: 테스트 재실행 → 통과(초록)** — Docker 데몬 필요.
  ```
  ./gradlew test --tests "com.commerce.integration.CouponRedeemIntegrationTest"
  ```
  기대: **PASS** — 3개 테스트(분개 T-account/클램프/min-spend) 통과, `verify().isBalanced == true`.

- [ ] **Step 9: 회귀 — 기존 redeem 경로(쿠폰 없음) 영향 없음 확인** — `ConcurrencyTest`는 `redemptionService.redeem`를 직접 호출하므로 불변. 컨트롤러 위임 경로의 무(無)쿠폰 동작은 `IdempotencyConcurrencyTest`(redeem HTTP)가 검증. Docker 데몬 필요.
  ```
  ./gradlew test --tests "com.commerce.integration.ConcurrencyTest" --tests "com.commerce.integration.IdempotencyConcurrencyTest"
  ```
  기대: **PASS**.

- [ ] **Step 10: 커밋**
  ```
  git add src/main/kotlin/com/komsco/voucher/voucher/infrastructure/VoucherLockManager.kt \
          src/main/kotlin/com/komsco/voucher/promotion/application/RedemptionOrchestrator.kt \
          src/main/kotlin/com/komsco/voucher/voucher/interfaces/dto/VoucherRequest.kt \
          src/main/kotlin/com/komsco/voucher/voucher/interfaces/VoucherController.kt \
          src/test/kotlin/com/komsco/voucher/support/TestFixtures.kt \
          src/test/kotlin/com/komsco/voucher/integration/CouponRedeemIntegrationTest.kt
  git commit -m "feat(promotion): 결합 결제 오케스트레이터(coupon→voucher 락, 2쌍 분개, 예산 보상) + redeem couponId 확장"
  ```

---

### Task 6: 동시성 — 동일 쿠폰 중복 사용 0 · 예산 초과 0 · 원장 isBalanced (스펙 §4.3, §8)

**Files:**
- Create: `src/test/kotlin/com/komsco/voucher/integration/CouponConcurrencyTest.kt`

**Interfaces:**
- Consumes: `RedemptionOrchestrator.redeem(...)`, `PromotionBudgetManager.consumed(...)`, `CouponRedemptionJpaRepository.sumActiveDiscountByPromotion(...)`, `LedgerVerificationService.verify()`, `TestFixtures.createPromotion/issueCoupon/issueVoucher`.

- [ ] **Step 1: 동시성 테스트 작성** — `src/test/kotlin/com/komsco/voucher/integration/CouponConcurrencyTest.kt`. `ConcurrencyTest`의 latch/executor 패턴 미러:
  ```kotlin
  package com.commerce.integration

  import com.commerce.common.exception.BusinessException
  import com.commerce.ledger.application.LedgerVerificationService
  import com.commerce.promotion.application.RedemptionOrchestrator
  import com.commerce.promotion.domain.CouponStatus
  import com.commerce.promotion.domain.DiscountType
  import com.commerce.promotion.infrastructure.CouponJpaRepository
  import com.commerce.promotion.infrastructure.CouponRedemptionJpaRepository
  import com.commerce.promotion.infrastructure.PromotionBudgetManager
  import com.commerce.support.IntegrationTestSupport
  import com.commerce.support.TestFixtures
  import com.commerce.voucher.infrastructure.VoucherJpaRepository
  import io.kotest.matchers.shouldBe
  import org.junit.jupiter.api.BeforeEach
  import org.junit.jupiter.api.Test
  import org.springframework.beans.factory.annotation.Autowired
  import java.math.BigDecimal
  import java.util.UUID
  import java.util.concurrent.CountDownLatch
  import java.util.concurrent.Executors
  import java.util.concurrent.atomic.AtomicInteger

  class CouponConcurrencyTest : IntegrationTestSupport() {

      @Autowired lateinit var fixtures: TestFixtures
      @Autowired lateinit var orchestrator: RedemptionOrchestrator
      @Autowired lateinit var voucherRepository: VoucherJpaRepository
      @Autowired lateinit var couponRepository: CouponJpaRepository
      @Autowired lateinit var couponRedemptionRepository: CouponRedemptionJpaRepository
      @Autowired lateinit var budgetManager: PromotionBudgetManager
      @Autowired lateinit var verificationService: LedgerVerificationService

      private var regionId: Long = 0
      private var merchantId: Long = 0

      @BeforeEach
      fun setup() {
          val region = fixtures.createRegion(code = UUID.randomUUID().toString().take(2).uppercase())
          val merchant = fixtures.createMerchant(region, fixtures.createMember())
          regionId = region.id
          merchantId = merchant.id
      }

      @Test
      fun `N threads redeeming the same coupon must use it exactly once (no double-use)`() {
          val member = fixtures.createMember()
          val voucher = fixtures.issueVoucher(member.id, regionId, BigDecimal("50000"))
          val promotion = fixtures.createPromotion(
              discountType = DiscountType.FIXED, discountValue = BigDecimal("3000"),
              budgetLimit = BigDecimal("1000000"),
          )
          val coupon = fixtures.issueCoupon(promotion.id, member.id)

          val threadCount = 10
          val latch = CountDownLatch(1)
          val executor = Executors.newFixedThreadPool(threadCount)
          val success = AtomicInteger(0)
          val alreadyUsed = AtomicInteger(0)

          val futures = (1..threadCount).map {
              executor.submit {
                  latch.await()
                  try {
                      orchestrator.redeem(voucher.id, merchantId, BigDecimal("10000"), coupon.id)
                      success.incrementAndGet()
                  } catch (e: BusinessException) {
                      if (e.errorCode == com.commerce.common.exception.ErrorCode.COUPON_ALREADY_USED)
                          alreadyUsed.incrementAndGet()
                  }
              }
          }
          latch.countDown()
          futures.forEach { it.get() }
          executor.shutdown()

          // 정확히 1회 사용
          success.get() shouldBe 1
          alreadyUsed.get() shouldBe 9
          couponRepository.findById(coupon.id).get().status shouldBe CouponStatus.REDEEMED
          couponRedemptionRepository.countByMemberIdAndPromotionIdAndCancelledFalse(member.id, promotion.id) shouldBe 1L
          // 바우처는 단 1회(7,000)만 차감
          voucherRepository.findById(voucher.id).get().balance.compareTo(BigDecimal("43000")) shouldBe 0
          verificationService.verify().isBalanced shouldBe true
      }

      @Test
      fun `N threads on a shared budget must never over-spend the promotion budget`() {
          // 예산 9,000 / 할인 3,000 → 정확히 3건만 성공
          val promotion = fixtures.createPromotion(
              discountType = DiscountType.FIXED, discountValue = BigDecimal("3000"),
              perMemberLimit = 1, budgetLimit = BigDecimal("9000"),
          )
          val threadCount = 10
          // 회원/바우처/쿠폰을 1:1:1로 준비(회원당 한도 1과 무관하게 예산만 핫스팟)
          data class Ctx(val voucherId: Long, val couponId: Long)
          val ctxs = (1..threadCount).map {
              val m = fixtures.createMember()
              val v = fixtures.issueVoucher(m.id, regionId, BigDecimal("50000"))
              val c = fixtures.issueCoupon(promotion.id, m.id)
              Ctx(v.id, c.id)
          }

          val latch = CountDownLatch(1)
          val executor = Executors.newFixedThreadPool(threadCount)
          val success = AtomicInteger(0)
          val budgetExceeded = AtomicInteger(0)

          val futures = ctxs.map { ctx ->
              executor.submit {
                  latch.await()
                  try {
                      orchestrator.redeem(ctx.voucherId, merchantId, BigDecimal("10000"), ctx.couponId)
                      success.incrementAndGet()
                  } catch (e: BusinessException) {
                      if (e.errorCode == com.commerce.common.exception.ErrorCode.PROMOTION_BUDGET_EXCEEDED)
                          budgetExceeded.incrementAndGet()
                  }
              }
          }
          latch.countDown()
          futures.forEach { it.get() }
          executor.shutdown()

          // 예산 9,000 / 3,000 = 정확히 3건 성공, 나머지 예산 초과
          success.get() shouldBe 3
          budgetExceeded.get() shouldBe 7
          // 소비 예산이 한도를 넘지 않음(보상으로 누수도 없음)
          budgetManager.consumed(promotion.id) shouldBe 9000L
          couponRedemptionRepository.sumActiveDiscountByPromotion(promotion.id)
              .compareTo(BigDecimal("9000")) shouldBe 0
          verificationService.verify().isBalanced shouldBe true
      }
  }
  ```

- [ ] **Step 2: 동시성 테스트 실행 → 통과(초록)** — Docker 데몬 필요.
  ```
  ./gradlew test --tests "com.commerce.integration.CouponConcurrencyTest"
  ```
  기대: **PASS** — 동일 쿠폰 1회 사용(9건 `COUPON_ALREADY_USED`), 공유 예산 3건 성공/7건 `PROMOTION_BUDGET_EXCEEDED`, 두 시나리오 모두 `isBalanced`.

- [ ] **Step 3: 커밋**
  ```
  git add src/test/kotlin/com/komsco/voucher/integration/CouponConcurrencyTest.kt
  git commit -m "test(promotion): 결합 결제 동시성 — 쿠폰 중복사용 0 · 예산 초과 0 · 원장 isBalanced"
  ```

---

### Task 7: 멱등성 — 동일 Idempotency-Key redeem exactly-once (스펙 §8)

**Files:**
- Create: `src/test/kotlin/com/komsco/voucher/integration/CouponIdempotencyTest.kt`

**Interfaces:**
- Consumes: `POST /api/v1/vouchers/{id}/redeem`(`@Idempotent`, couponId 확장), `TestFixtures.createPromotion/issueCoupon/issueVoucher`, `CouponRedemptionJpaRepository`, `CouponJpaRepository`, `TransactionJpaRepository.countByVoucherIdAndStatus`.

- [ ] **Step 1: 멱등성 HTTP 테스트 작성** — `src/test/kotlin/com/komsco/voucher/integration/CouponIdempotencyTest.kt`. `IdempotencyConcurrencyTest`의 RANDOM_PORT + 자체 컨테이너 구조를 미러(쿠폰 발급은 서비스로 시드 → 인증 불필요, redeem은 body merchantId라 인증 불필요):
  ```kotlin
  package com.commerce.integration

  import com.commerce.promotion.domain.CouponStatus
  import com.commerce.promotion.infrastructure.CouponJpaRepository
  import com.commerce.promotion.infrastructure.CouponRedemptionJpaRepository
  import com.commerce.support.TestFixtures
  import com.commerce.transaction.domain.TransactionStatus
  import com.commerce.transaction.infrastructure.TransactionJpaRepository
  import com.commerce.voucher.infrastructure.VoucherJpaRepository
  import io.kotest.matchers.shouldBe
  import org.junit.jupiter.api.BeforeEach
  import org.junit.jupiter.api.Test
  import org.springframework.beans.factory.annotation.Autowired
  import org.springframework.boot.test.context.SpringBootTest
  import org.springframework.boot.test.web.client.TestRestTemplate
  import org.springframework.http.HttpEntity
  import org.springframework.http.HttpHeaders
  import org.springframework.http.MediaType
  import org.springframework.test.context.ActiveProfiles
  import org.springframework.test.context.DynamicPropertyRegistry
  import org.springframework.test.context.DynamicPropertySource
  import org.testcontainers.containers.GenericContainer
  import org.testcontainers.containers.MySQLContainer
  import org.testcontainers.junit.jupiter.Testcontainers
  import java.math.BigDecimal
  import java.util.UUID
  import java.util.concurrent.CountDownLatch
  import java.util.concurrent.Executors
  import java.util.concurrent.atomic.AtomicInteger

  @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
  @ActiveProfiles("test")
  @Testcontainers
  class CouponIdempotencyTest {

      companion object {
          val mysql = MySQLContainer("mysql:8.0").apply {
              withDatabaseName("voucher_test")
              withUsername("test")
              withPassword("test")
              withCommand("--character-set-server=utf8mb4", "--collation-server=utf8mb4_unicode_ci")
              start()
          }
          val redis = GenericContainer<Nothing>("redis:7-alpine").apply {
              withExposedPorts(6379)
              start()
          }

          @JvmStatic
          @DynamicPropertySource
          fun properties(registry: DynamicPropertyRegistry) {
              registry.add("spring.datasource.url") { mysql.jdbcUrl }
              registry.add("spring.datasource.username") { mysql.username }
              registry.add("spring.datasource.password") { mysql.password }
              registry.add("spring.data.redis.host") { redis.host }
              registry.add("spring.data.redis.port") { redis.getMappedPort(6379).toString() }
          }
      }

      @Autowired lateinit var restTemplate: TestRestTemplate
      @Autowired lateinit var fixtures: TestFixtures
      @Autowired lateinit var voucherRepository: VoucherJpaRepository
      @Autowired lateinit var couponRepository: CouponJpaRepository
      @Autowired lateinit var couponRedemptionRepository: CouponRedemptionJpaRepository
      @Autowired lateinit var transactionRepository: TransactionJpaRepository

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
      fun `same Idempotency-Key concurrent coupon-redeem executes exactly once`() {
          val voucher = fixtures.issueVoucher(memberId, regionId, BigDecimal("50000"))
          val promotion = fixtures.createPromotion(
              discountType = com.commerce.promotion.domain.DiscountType.FIXED,
              discountValue = BigDecimal("3000"), budgetLimit = BigDecimal("1000000"),
          )
          val coupon = fixtures.issueCoupon(promotion.id, memberId)

          val idempotencyKey = UUID.randomUUID().toString()
          val threadCount = 10
          val headers = HttpHeaders().apply {
              contentType = MediaType.APPLICATION_JSON
              set("Idempotency-Key", idempotencyKey)
          }
          val body = """{"merchantId": $merchantId, "amount": 10000, "couponId": ${coupon.id}}"""
          val request = HttpEntity(body, headers)
          val url = "/api/v1/vouchers/${voucher.id}/redeem"

          val latch = CountDownLatch(1)
          val executor = Executors.newFixedThreadPool(threadCount)
          val success2xx = AtomicInteger(0)
          val futures = (1..threadCount).map {
              executor.submit {
                  latch.await()
                  val response = restTemplate.postForEntity(url, request, String::class.java)
                  if (response.statusCode.is2xxSuccessful) success2xx.incrementAndGet()
              }
          }
          latch.countDown()
          futures.forEach { it.get() }
          executor.shutdown()

          // 단 1회(T-D=7,000)만 차감 → 잔액 43,000
          voucherRepository.findById(voucher.id).get().balance.compareTo(BigDecimal("43000")) shouldBe 0
          // 쿠폰 1회 사용 + CouponRedemption 1건
          couponRepository.findById(coupon.id).get().status shouldBe CouponStatus.REDEEMED
          couponRedemptionRepository.countByMemberIdAndPromotionIdAndCancelledFalse(memberId, promotion.id) shouldBe 1L
          // 완료 거래 = 발행 1 + 결제 1 = 2 (이중 처리면 늘어남)
          transactionRepository.countByVoucherIdAndStatus(voucher.id, TransactionStatus.COMPLETED) shouldBe 2
      }
  }
  ```

- [ ] **Step 2: 멱등성 테스트 실행 → 통과(초록)** — Docker 데몬 필요.
  ```
  ./gradlew test --tests "com.commerce.integration.CouponIdempotencyTest"
  ```
  기대: **PASS** — 동일 키 10건 동시 호출에도 결제·쿠폰 사용·CouponRedemption이 정확히 1회.

- [ ] **Step 3: 커밋**
  ```
  git add src/test/kotlin/com/komsco/voucher/integration/CouponIdempotencyTest.kt
  git commit -m "test(promotion): 쿠폰 결제 멱등성 — 동일 Idempotency-Key exactly-once"
  ```

---

### Task 8: 취소/환불 — 두 leg 쌍 역분개 + 바우처 복원 + 쿠폰 CANCELLED + 예산 반환 (스펙 §4.4)

**Files:**
- Modify: `src/main/kotlin/com/komsco/voucher/transaction/application/TransactionCancelService.kt` (전체 — 결합결제 인지 분기 추가)
- Create: `src/test/kotlin/com/komsco/voucher/integration/CouponCancelIntegrationTest.kt`

**Interfaces:**
- Consumes (locked contract): `LedgerService.record(...)`, `AccountCode.VOUCHER_BALANCE/MERCHANT_RECEIVABLE/PROMOTION_FUNDING`, `LedgerEntryType.CANCELLATION`, `tx.requestCancel()/cancel()/complete()`, `voucher.restoreBalance(amount)`, `VoucherLockManager.withCouponLock/withVoucherLock`, `Coupon.cancel()`, `CouponRedemption.markCancelled()`, `PromotionBudgetManager.release(...)`, `CouponRedemptionJpaRepository.findByTransactionId(...)`, `CouponJpaRepository`.
- Produces: 결합결제 취소 시 두 leg 쌍 모두 역분개 + 바우처 `T−D` 복원 + 쿠폰 `REDEEMED→CANCELLED` + `CouponRedemption.cancelled=true` + 예산 `DECRBY`. 무(無)쿠폰 거래는 기존 동작 유지.

- [ ] **Step 1: 취소 통합 테스트 작성** — `src/test/kotlin/com/komsco/voucher/integration/CouponCancelIntegrationTest.kt`:
  ```kotlin
  package com.commerce.integration

  import com.commerce.ledger.application.LedgerVerificationService
  import com.commerce.promotion.application.RedemptionOrchestrator
  import com.commerce.promotion.domain.CouponStatus
  import com.commerce.promotion.domain.DiscountType
  import com.commerce.promotion.infrastructure.CouponJpaRepository
  import com.commerce.promotion.infrastructure.CouponRedemptionJpaRepository
  import com.commerce.promotion.infrastructure.PromotionBudgetManager
  import com.commerce.support.IntegrationTestSupport
  import com.commerce.support.TestFixtures
  import com.commerce.transaction.application.TransactionCancelService
  import com.commerce.transaction.domain.TransactionStatus
  import com.commerce.transaction.infrastructure.TransactionJpaRepository
  import com.commerce.voucher.infrastructure.VoucherJpaRepository
  import io.kotest.matchers.shouldBe
  import org.junit.jupiter.api.BeforeEach
  import org.junit.jupiter.api.Test
  import org.springframework.beans.factory.annotation.Autowired
  import java.math.BigDecimal
  import java.util.UUID

  class CouponCancelIntegrationTest : IntegrationTestSupport() {

      @Autowired lateinit var fixtures: TestFixtures
      @Autowired lateinit var orchestrator: RedemptionOrchestrator
      @Autowired lateinit var cancelService: TransactionCancelService
      @Autowired lateinit var voucherRepository: VoucherJpaRepository
      @Autowired lateinit var couponRepository: CouponJpaRepository
      @Autowired lateinit var couponRedemptionRepository: CouponRedemptionJpaRepository
      @Autowired lateinit var transactionRepository: TransactionJpaRepository
      @Autowired lateinit var budgetManager: PromotionBudgetManager
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
      fun `cancelling a coupon-applied redeem reverses both ledger pairs and restores everything`() {
          val voucher = fixtures.issueVoucher(memberId, regionId, BigDecimal("50000"))
          val promotion = fixtures.createPromotion(
              discountType = DiscountType.FIXED, discountValue = BigDecimal("3000"),
              budgetLimit = BigDecimal("1000000"),
          )
          val coupon = fixtures.issueCoupon(promotion.id, memberId)

          val result = orchestrator.redeem(voucher.id, merchantId, BigDecimal("10000"), coupon.id)
          budgetManager.consumed(promotion.id) shouldBe 3000L
          voucherRepository.findById(voucher.id).get().balance.compareTo(BigDecimal("43000")) shouldBe 0

          val compensatingTxId = cancelService.cancel(result.transactionId)

          // 바우처 잔액 T-D(7,000) 복원 → 50,000
          voucherRepository.findById(voucher.id).get().balance.compareTo(BigDecimal("50000")) shouldBe 0
          // 쿠폰 CANCELLED + CouponRedemption.cancelled
          couponRepository.findById(coupon.id).get().status shouldBe CouponStatus.CANCELLED
          couponRedemptionRepository.findByTransactionId(result.transactionId)!!.cancelled shouldBe true
          // 예산 반환 → 0
          budgetManager.consumed(promotion.id) shouldBe 0L
          couponRedemptionRepository.sumActiveDiscountByPromotion(promotion.id)
              .compareTo(BigDecimal.ZERO) shouldBe 0
          // 원 거래 CANCELLED, 보상 거래 COMPLETED
          transactionRepository.findById(result.transactionId).get().status shouldBe TransactionStatus.CANCELLED
          transactionRepository.findById(compensatingTxId).get().status shouldBe TransactionStatus.COMPLETED
          // 글로벌 정합성 유지
          verificationService.verify().isBalanced shouldBe true
      }
  }
  ```

- [ ] **Step 2: 테스트 실행 → 실패 확인(빨강)** — 현재 `cancel`은 결합결제를 모르고 바우처 잔액을 `original.amount(=T=10,000)`만큼 복원 + 쿠폰/예산 미처리. Docker 데몬 필요.
  ```
  ./gradlew test --tests "com.commerce.integration.CouponCancelIntegrationTest"
  ```
  기대: **FAIL** — 쿠폰 상태가 `REDEEMED`(미취소)이고 잔액이 53,000(과복원)/예산 미반환으로 단언 실패.

- [ ] **Step 3: `TransactionCancelService`를 결합결제 인지로 확장** — `src/main/kotlin/com/komsco/voucher/transaction/application/TransactionCancelService.kt` 전체를 다음으로 교체(무쿠폰 경로는 기존 로직 보존):
  ```kotlin
  package com.commerce.transaction.application

  import com.commerce.common.exception.BusinessException
  import com.commerce.common.exception.ErrorCode
  import com.commerce.ledger.application.LedgerService
  import com.commerce.ledger.domain.AccountCode
  import com.commerce.ledger.domain.LedgerEntryType
  import com.commerce.promotion.domain.CouponRedemption
  import com.commerce.promotion.infrastructure.CouponJpaRepository
  import com.commerce.promotion.infrastructure.CouponRedemptionJpaRepository
  import com.commerce.promotion.infrastructure.PromotionBudgetManager
  import com.commerce.transaction.domain.TransactionType
  import com.commerce.transaction.domain.event.TransactionCancelledEvent
  import com.commerce.transaction.infrastructure.TransactionJpaRepository
  import com.commerce.voucher.infrastructure.VoucherJpaRepository
  import com.commerce.voucher.infrastructure.VoucherLockManager
  import org.springframework.context.ApplicationEventPublisher
  import org.springframework.stereotype.Service
  import org.springframework.transaction.support.TransactionTemplate

  @Service
  class TransactionCancelService(
      private val transactionRepository: TransactionJpaRepository,
      private val transactionService: TransactionService,
      private val voucherRepository: VoucherJpaRepository,
      private val lockManager: VoucherLockManager,
      private val ledgerService: LedgerService,
      private val eventPublisher: ApplicationEventPublisher,
      private val transactionTemplate: TransactionTemplate,
      private val couponRedemptionRepository: CouponRedemptionJpaRepository,
      private val couponRepository: CouponJpaRepository,
      private val budgetManager: PromotionBudgetManager,
  ) {

      /**
       * 거래 취소 (보상 트랜잭션). 분산락 → 트랜잭션(커밋) → 락 해제 순서 보장.
       * 결합결제(쿠폰 적용)면 정준 락 순서 coupon → voucher 로 잠그고 두 leg 쌍을 모두 역분개한다.
       */
      fun cancel(transactionId: Long): Long {
          val voucherId = transactionService.getById(transactionId).voucherId
              ?: throw BusinessException(ErrorCode.INVALID_INPUT, "상품권 거래만 취소할 수 있습니다")

          val couponRedemption = couponRedemptionRepository.findByTransactionId(transactionId)

          return if (couponRedemption != null) {
              lockManager.withCouponLock(couponRedemption.couponId) {
                  lockManager.withVoucherLock(voucherId) {
                      cancelWithCoupon(transactionId, voucherId, couponRedemption)
                  }
              }
          } else {
              lockManager.withVoucherLock(voucherId) {
                  cancelVoucherOnly(transactionId, voucherId)
              }
          }
      }

      /** 무쿠폰(기존) 취소: 단일 역분개 + 바우처 잔액 복원. */
      private fun cancelVoucherOnly(transactionId: Long, voucherId: Long): Long =
          transactionTemplate.execute { _ ->
              val original = transactionRepository.findById(transactionId)
                  .orElseThrow { BusinessException(ErrorCode.ENTITY_NOT_FOUND) }
              original.requestCancel()

              val compensating = transactionService.create(
                  type = TransactionType.CANCELLATION,
                  amount = original.amount,
                  voucherId = voucherId,
                  merchantId = original.merchantId,
                  originalTransactionId = original.id,
              )
              ledgerService.record(
                  debitAccount = AccountCode.VOUCHER_BALANCE,
                  creditAccount = AccountCode.MERCHANT_RECEIVABLE,
                  amount = original.amount,
                  transactionId = compensating.id,
                  entryType = LedgerEntryType.CANCELLATION,
              )
              compensating.complete()
              original.cancel()

              val voucher = voucherRepository.findByIdForUpdate(voucherId)
                  ?: throw BusinessException(ErrorCode.ENTITY_NOT_FOUND)
              voucher.restoreBalance(original.amount)

              eventPublisher.publishEvent(TransactionCancelledEvent(original.id, voucherId, original.amount))
              compensating.id
          }!!

      /**
       * 결합결제 취소: 두 leg 쌍을 모두 역분개(보상 트랜잭션, 원 거래 불변 보존).
       * - 쌍1 역분개: DEBIT VOUCHER_BALANCE / CREDIT MERCHANT_RECEIVABLE (T-D)
       * - 쌍2 역분개: DEBIT PROMOTION_FUNDING / CREDIT MERCHANT_RECEIVABLE (D)
       * 바우처 잔액 T-D 복원, 쿠폰 CANCELLED, 예산 반환(DECRBY).
       */
      private fun cancelWithCoupon(
          transactionId: Long,
          voucherId: Long,
          couponRedemption: CouponRedemption,
      ): Long {
          val compensatingId = transactionTemplate.execute { _ ->
              val original = transactionRepository.findById(transactionId)
                  .orElseThrow { BusinessException(ErrorCode.ENTITY_NOT_FOUND) }
              original.requestCancel()

              val compensating = transactionService.create(
                  type = TransactionType.CANCELLATION,
                  amount = original.amount, // gross T
                  voucherId = voucherId,
                  merchantId = original.merchantId,
                  originalTransactionId = original.id,
              )

              // 쌍1 역분개 (바우처 결제분)
              ledgerService.record(
                  debitAccount = AccountCode.VOUCHER_BALANCE,
                  creditAccount = AccountCode.MERCHANT_RECEIVABLE,
                  amount = couponRedemption.voucherCharged,
                  transactionId = compensating.id,
                  entryType = LedgerEntryType.CANCELLATION,
              )
              // 쌍2 역분개 (플랫폼 보조분)
              ledgerService.record(
                  debitAccount = AccountCode.PROMOTION_FUNDING,
                  creditAccount = AccountCode.MERCHANT_RECEIVABLE,
                  amount = couponRedemption.discountAmount,
                  transactionId = compensating.id,
                  entryType = LedgerEntryType.CANCELLATION,
              )
              compensating.complete()
              original.cancel()

              // 바우처 잔액 T-D 복원
              val voucher = voucherRepository.findByIdForUpdate(voucherId)
                  ?: throw BusinessException(ErrorCode.ENTITY_NOT_FOUND)
              voucher.restoreBalance(couponRedemption.voucherCharged)

              // 쿠폰 회수 + 사용 기록 취소 표시
              val coupon = couponRepository.findById(couponRedemption.couponId)
                  .orElseThrow { BusinessException(ErrorCode.COUPON_NOT_FOUND) }
              coupon.cancel()
              couponRedemption.markCancelled()
              couponRedemptionRepository.save(couponRedemption)

              eventPublisher.publishEvent(TransactionCancelledEvent(original.id, voucherId, original.amount))
              compensating.id
          }!!

          // 커밋 성공 후 예산 반환(누락돼도 재동기화 잡이 DB 기준으로 보정)
          budgetManager.release(couponRedemption.promotionId, couponRedemption.discountAmount)
          return compensatingId
      }
  }
  ```

- [ ] **Step 4: 취소 테스트 재실행 → 통과(초록)** — Docker 데몬 필요.
  ```
  ./gradlew test --tests "com.commerce.integration.CouponCancelIntegrationTest"
  ```
  기대: **PASS** — 잔액 복원(50,000), 쿠폰 CANCELLED, 예산 0, 원 거래 CANCELLED/보상 COMPLETED, `isBalanced`.

- [ ] **Step 5: 회귀 — 무쿠폰 취소 경로 영향 없음 확인** — `cancelVoucherOnly`가 기존 분기와 동일함을 기존 취소 관련 통합 테스트(있다면)로 확인. 본 변경은 분기만 추가하므로 무쿠폰 경로 불변. Docker 데몬 필요.
  ```
  ./gradlew test --tests "com.commerce.integration.*"
  ```
  기대: **PASS** — 쿠폰/무쿠폰 통합·동시성·멱등성 전부 통과.

- [ ] **Step 6: 커밋**
  ```
  git add src/main/kotlin/com/komsco/voucher/transaction/application/TransactionCancelService.kt \
          src/test/kotlin/com/komsco/voucher/integration/CouponCancelIntegrationTest.kt
  git commit -m "feat(transaction): 결합결제 취소 — 두 leg 쌍 역분개 + 바우처 복원 + 쿠폰 CANCELLED + 예산 반환"
  ```

---

## Done criteria (Plan 2 전체)
- [ ] `promotion` 패키지에 `Promotion`/`Coupon`/`CouponRedemption` + 리포지토리 + `V2__promotion_coupon.sql`(validate 통과).
- [ ] `POST /api/v1/promotions`(생성), `POST /api/v1/promotions/{id}/coupons`(`@Idempotent`, principal 신원), `GET /api/v1/members/{memberId}/coupons`(본인) 동작.
- [ ] 예산: Redis Lua 원자 예약 + 보상 DECRBY + 재동기화 스케줄러.
- [ ] 결합 오케스트레이터: `coupon→voucher` 락, txId 공유 2쌍 분개(REDEMPTION + COUPON_SUBSIDY), 클램프/min-spend/회원 한도/0·음수 거부.
- [ ] 취소/환불: 두 leg 쌍 역분개 + 바우처 `T−D` 복원 + 쿠폰 `CANCELLED` + 예산 반환.
- [ ] 테스트 PASS: `PromotionTest`/`CouponTest`(단위), `PromotionPersistenceTest`/`PromotionBudgetManagerTest`/`CouponIssueServiceTest`/`CouponRedeemIntegrationTest`(통합), `CouponConcurrencyTest`(동시성), `CouponIdempotencyTest`(멱등성), `CouponCancelIntegrationTest`(취소). 모두 `verify().isBalanced == true`.
- [ ] `./gradlew test` 전체 PASS(Docker 데몬 가동 시).

## Notes / 가정 / STRETCH 문서화
- **가정 1 (redeem 진입점)**: 결합 결제는 기존 `POST /api/v1/vouchers/{id}/redeem`를 `RedeemRequest.couponId`로 확장해 `@Idempotent`·멱등 인프라를 재사용한다(스펙 §4.1의 "redeem 확장"). `RedeemRequest.amount`는 **주문 총액 T(gross)** 로 의미를 명확화했고, 쿠폰이 없으면 `T==T−D`로 기존 동작과 동일.
- **가정 2 (가맹점 인증 = STRETCH)**: redeem의 `merchantId`는 가맹점이 `Member`/`MemberRole`과 별개 엔티티이므로 Plan 1의 회원 principal로 도출할 수 없다 → 당분간 body 유지(스펙 §6.1, Plan 1 STRETCH와 정합). 쿠폰 발급/조회만 회원 principal(`SecurityUtils.currentMemberId()`)을 강제.
- **가정 3 (회원당 사용 한도 = 사용 시점 집계)**: `perMemberLimit`은 redeem 시 `CouponRedemption`(미취소) 건수로 강제한다(발급은 멱등키로만 중복 방지). 스펙의 "회원당 사용 한도"를 직접 구현.
- **가정 4 (RESERVED 상태)**: `CouponStatus.RESERVED`는 스펙 엔티티 정의를 보존하기 위해 enum에 포함하되, MUST 동기 흐름은 단일 DB tx + 쿠폰 락으로 `ISSUED→REDEEMED` 직행한다(별도 영속 RESERVED 단계 불필요). 비동기 2단계 예약은 STRETCH.
- **가정 5 (예산 진실원천)**: 라이브 제어는 Redis 카운터, DB 진실원천은 append-only `CouponRedemption.discountAmount` 합계. `Promotion`에 가변 `budgetConsumed` 캐시를 두지 않아 갱신 경합/이중 진실을 제거(지역 카운터가 voucher 합계를 진실원천으로 쓰는 패턴과 동형). 보상 DECRBY 누락/Redis 재시작은 재동기화 잡이 매시 보정.
- **가정 6 (금액 정수 원)**: 예산 Lua는 `longValueExact()`를 쓰므로 모든 금액은 정수 원이어야 한다. 정률 할인은 `RoundingMode.DOWN`로 0원 단위 내림(과할인·예산 누수 방지). 소수 원 입력은 가맹점/주문 단에서 정수화 가정.
- **가정 7 (정합성 검증 범위)**: `PROMOTION_FUNDING`은 대변정상이라 글로벌 `차변합==대변합` 불변식이 자동 커버하고 per-voucher `VOUCHER_BALANCE` 검증에 영향을 주지 않는다 → `LedgerVerificationService`는 Plan 2에서 수정 불필요(스펙 §3.2). `POINT_BALANCE` 전역 불변식 추가는 Plan 3.
- **V2 validate 실패 시 대응**: Task 2 Step 9에서 `SchemaValidationException`이 나면 `ddl-auto`를 되돌리지 말고, `V1__baseline.sql`이 동종 컬럼을 emit한 형태(decimal/datetime/bit 등)에 맞춰 `V2`의 컬럼 정의를 수정한다(Plan 1 §6.2 의도: 불일치 즉시 노출·교정). 필요 시 Plan 1의 `SchemaExportTest` 기법으로 신규 3테이블 DDL을 재생성해 대조한다.
- **STRETCH (범위 밖)**: 쿠폰 스택(다중 쿠폰), 포인트 결제수단(tender)·포인트 적립 회수 연동, 쿠폰 만료 배치(`breakage`), 가맹점 인증, AI 프로모션 초안(`POST /api/v1/promotions/draft`, Plan 4).
