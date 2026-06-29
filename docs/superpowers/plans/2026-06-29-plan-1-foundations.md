# Plan 1 — FOUNDATIONS (재무 계정 · Flyway 베이스라인 · JWT 필터)

> 베이스 스펙: `docs/superpowers/specs/2026-06-29-commerce-repositioning-design.md` (§3 재무 모델, §6.1 JWT, §6.2 Flyway, §11).
> 이 계획은 후속 Plan 2~4(쿠폰/포인트/AI)가 의존하는 **Day 1 선결 작업**만 다룬다.

## Goal
커머스 도메인 확장 이전에 깔아야 할 토대 3종을 완성한다.
- **(A) 재무 계정/분개 타입 확장**: `AccountCode`에 `PROMOTION_FUNDING`, `POINT_BALANCE`, `POINT_FUNDING` 3종, `LedgerEntryType`에 `COUPON_SUBSIDY`, `POINT_EARN` 2종을 추가하고 존재를 단위 테스트로 못 박는다. (스펙 §3.2)
- **(B) Flyway 조기 베이스라인**: 현재 Hibernate 스키마를 MySQL DDL로 export 하여 `V1__baseline.sql`로 만들고, `ddl-auto: update → validate` + `flyway.enabled: true`로 전환한다. 베이스라인이 엔티티와 불일치하면 통합 테스트가 즉시 깨지도록 테스트 프로파일도 함께 전환한다. (스펙 §6.2)
- **(C) JWT 인증 필터 + principal 헬퍼**: `JwtAuthenticationFilter(OncePerRequestFilter)`를 보안 체인에 연결하고, 신규 컨트롤러(Plan 2~4)가 신원을 도출할 `SecurityUtils.currentMemberId()` 헬퍼와 이를 사용하는 `GET /api/v1/me` 보호 엔드포인트를 추가한다. 토큰 없으면 401, 유효 토큰이면 200을 통합 테스트로 증명한다. (스펙 §6.1)

## Architecture
- 패키지 루트 `com.commerce`. 기능별 하위 레이어 `interfaces / application / domain / infrastructure`.
- 원장은 `ledger/domain`(enum·`LedgerEntry`)과 `ledger/application`(`LedgerService`·`LedgerVerificationService`). enum 값은 `LedgerEntry`에 `@Enumerated(EnumType.STRING)`으로 저장되므로 **enum 추가는 스키마를 변경하지 않는다**(컬럼 길이만 확인). → (A)는 (B)의 베이스라인 재생성을 유발하지 않는다.
- 인증은 `config` 패키지(`SecurityConfig`, `JwtTokenProvider`). 신규 필터는 `config`, principal 헬퍼는 `common/security`, 신원 데모 엔드포인트는 `member/interfaces`(신원은 회원 중심).
- Flyway 마이그레이션은 `src/main/resources/db/migration`(classpath 기본 위치). 테스트는 Testcontainers MySQL이 비어 있으므로 `V1`이 그대로 적용되고 Hibernate `validate`가 검증한다.

## Tech Stack
- Gradle Kotlin DSL, Spring Boot 3.2.5, Kotlin 1.9.23, Java 17, Hibernate 6.4(부트 3.2 동봉).
- MySQL 8 / Redis 7, Testcontainers 1.19.7, Kotest 5.8.1, MockK 1.13.10.
- 단일 테스트 실행: `./gradlew test --tests "com.commerce.<FqcnTest>"` (PowerShell/Git Bash 공통; Docker 데몬 필요한 통합 테스트는 명시).

## Global Constraints
- **LOCKED CONTRACT 준수**: enum 값 이름/설명, `LedgerService.record(...)` 시그니처, `JwtTokenProvider`의 `generateToken/validateToken/getMemberIdFromToken/getRoleFromToken`, 필터가 세팅하는 `UsernamePasswordAuthenticationToken(principal = memberId: Long, credentials = null, authorities = [SimpleGrantedAuthority("ROLE_"+role)])`, `http.addFilterBefore(filter, UsernamePasswordAuthenticationFilter::class.java)`를 **글자 그대로** 따른다.
- **신규 엔드포인트는 body의 memberId를 신뢰하지 않는다**. 신원은 항상 `SecurityUtils.currentMemberId()`(=인증 principal)에서 얻는다. 가맹점 인증은 STRETCH(문서화만).
- **enum 컬럼 길이 확인**: `LedgerEntry.account` `length=30`(최장 `PROMOTION_FUNDING`=17 ✔), `LedgerEntry.entryType` `length=20`(최장 `MANUAL_ADJUSTMENT`=17 / 신규 `COUPON_SUBSIDY`=14 ✔). 길이 변경 불필요.
- **TDD**: 각 태스크는 실패하는 테스트를 먼저 추가하고(빨강), 구현 후 통과(초록)시킨다. 각 태스크 끝에서 git commit.
- **기존 분개 규약 불변**: 기존 `record(...)` 2-leg 헬퍼 시그니처를 바꾸지 않는다(3-leg는 후속 Plan에서 txId 공유 2쌍으로 표현). 본 계획에서는 enum/계정만 추가한다.
- **플레이스홀더 금지**: 모든 코드 스텝은 완전한 Kotlin. 모든 테스트 스텝은 정확한 gradle 명령 + 기대 PASS/FAIL.
- Windows 작업 디렉터리 루트는 리포 루트. Gradle `Test` 태스크의 작업 디렉터리는 프로젝트 루트이므로 상대 경로 `src/main/resources/db/migration/...`가 그대로 해석된다.

---

### Task 1: 재무 계정 · 분개 타입 확장 (스펙 §3.2)

**Files:**
- Create: `src/test/kotlin/com/komsco/voucher/ledger/domain/AccountCodeAndEntryTypeTest.kt`
- Modify: `src/main/kotlin/com/komsco/voucher/ledger/domain/AccountCode.kt` (현재 3~11행 enum 본문에 3개 상수 추가)
- Modify: `src/main/kotlin/com/komsco/voucher/ledger/domain/LedgerEntryType.kt` (현재 3~5행 `LedgerEntryType` 본문에 2개 상수 추가)

**Interfaces:**
- Consumes (locked contract): `enum class AccountCode(val description: String)`, `enum class LedgerEntryType`.
- Produces:
  - `AccountCode.PROMOTION_FUNDING` (description `"프로모션 출연금"`)
  - `AccountCode.POINT_BALANCE` (description `"포인트 잔액"`)
  - `AccountCode.POINT_FUNDING` (description `"포인트 출연금"`)
  - `LedgerEntryType.COUPON_SUBSIDY`
  - `LedgerEntryType.POINT_EARN`

- [ ] **Step 1: 실패 단위 테스트 작성** — 새 파일 `src/test/kotlin/com/komsco/voucher/ledger/domain/AccountCodeAndEntryTypeTest.kt`:
  ```kotlin
  package com.commerce.ledger.domain

  import io.kotest.matchers.shouldBe
  import org.junit.jupiter.api.Test

  class AccountCodeAndEntryTypeTest {

      @Test
      fun `new account codes exist with Korean descriptions`() {
          AccountCode.PROMOTION_FUNDING.description shouldBe "프로모션 출연금"
          AccountCode.POINT_BALANCE.description shouldBe "포인트 잔액"
          AccountCode.POINT_FUNDING.description shouldBe "포인트 출연금"
      }

      @Test
      fun `new account codes are resolvable by name`() {
          AccountCode.valueOf("PROMOTION_FUNDING") shouldBe AccountCode.PROMOTION_FUNDING
          AccountCode.valueOf("POINT_BALANCE") shouldBe AccountCode.POINT_BALANCE
          AccountCode.valueOf("POINT_FUNDING") shouldBe AccountCode.POINT_FUNDING
      }

      @Test
      fun `new ledger entry types exist`() {
          LedgerEntryType.valueOf("COUPON_SUBSIDY") shouldBe LedgerEntryType.COUPON_SUBSIDY
          LedgerEntryType.valueOf("POINT_EARN") shouldBe LedgerEntryType.POINT_EARN
      }

      @Test
      fun `enum string lengths fit ledger column limits`() {
          // LedgerEntry.account length=30, entryType length=20
          AccountCode.entries.forEach { it.name.length shouldBe it.name.length.coerceAtMost(30) }
          LedgerEntryType.entries.forEach { it.name.length shouldBe it.name.length.coerceAtMost(20) }
      }
  }
  ```

- [ ] **Step 2: 테스트 실행 → 컴파일 실패 확인(빨강)** — 신규 상수 미존재로 컴파일 단계에서 실패해야 한다.
  ```
  ./gradlew test --tests "com.commerce.ledger.domain.AccountCodeAndEntryTypeTest"
  ```
  기대: **FAIL** — `Unresolved reference: PROMOTION_FUNDING` (및 POINT_BALANCE/POINT_FUNDING/COUPON_SUBSIDY/POINT_EARN). Docker 불필요(순수 단위 테스트).

- [ ] **Step 3: `AccountCode`에 3개 상수 추가** — `src/main/kotlin/com/komsco/voucher/ledger/domain/AccountCode.kt`의 `SETTLEMENT_PAYABLE("정산 미지급금"),` 줄 다음에 추가하여 전체를 다음으로 만든다:
  ```kotlin
  package com.commerce.ledger.domain

  enum class AccountCode(val description: String) {
      MEMBER_CASH("회원 현금"),
      VOUCHER_BALANCE("상품권 잔액"),
      MERCHANT_RECEIVABLE("가맹점 미수금"),
      REVENUE_DISCOUNT("할인 수익"),
      EXPIRED_VOUCHER("만료 상품권"),
      REFUND_PAYABLE("환불 미지급금"),
      SETTLEMENT_PAYABLE("정산 미지급금"),

      // 커머스 확장 (스펙 §3.2): 플랫폼 펀딩·정산 gross 모델 전용 계정
      PROMOTION_FUNDING("프로모션 출연금"), // 대변정상: 플랫폼 쿠폰 보조 누적
      POINT_BALANCE("포인트 잔액"),         // 차변정상: VOUCHER_BALANCE와 동일 취급
      POINT_FUNDING("포인트 출연금"),       // 대변정상: 플랫폼 포인트 적립 출연
  }
  ```

- [ ] **Step 4: `LedgerEntryType`에 2개 상수 추가** — `src/main/kotlin/com/komsco/voucher/ledger/domain/LedgerEntryType.kt`의 `LedgerEntryType`만 수정(`LedgerEntrySide`는 불변):
  ```kotlin
  package com.commerce.ledger.domain

  enum class LedgerEntryType {
      PURCHASE, REDEMPTION, REFUND, WITHDRAWAL, EXPIRY, SETTLEMENT, CANCELLATION, MANUAL_ADJUSTMENT,
      // 커머스 확장 (스펙 §3.2)
      COUPON_SUBSIDY, // 쿠폰 보조 분개 (DEBIT MERCHANT_RECEIVABLE / CREDIT PROMOTION_FUNDING)
      POINT_EARN,     // 포인트 적립 분개 (DEBIT POINT_BALANCE / CREDIT POINT_FUNDING)
  }

  enum class LedgerEntrySide {
      DEBIT, CREDIT
  }
  ```

- [ ] **Step 5: 테스트 재실행 → 통과(초록)**
  ```
  ./gradlew test --tests "com.commerce.ledger.domain.AccountCodeAndEntryTypeTest"
  ```
  기대: **PASS** — 4개 테스트 모두 통과.

- [ ] **Step 6: 커밋**
  ```
  git add src/main/kotlin/com/komsco/voucher/ledger/domain/AccountCode.kt \
          src/main/kotlin/com/komsco/voucher/ledger/domain/LedgerEntryType.kt \
          src/test/kotlin/com/komsco/voucher/ledger/domain/AccountCodeAndEntryTypeTest.kt
  git commit -m "feat(ledger): 커머스 확장용 계정/분개 타입 추가 (PROMOTION_FUNDING, POINT_BALANCE, POINT_FUNDING, COUPON_SUBSIDY, POINT_EARN)"
  ```

---

### Task 2: Flyway 조기 베이스라인 + validate 전환 (스펙 §6.2)

> 전략: 현재 안정 스키마를 Hibernate가 MySQL 방언으로 export → `V1__baseline.sql`. 이후 메인/테스트 프로파일을 동시에 `validate + flyway.enabled:true`로 전환해 베이스라인↔엔티티 불일치를 통합 테스트가 즉시 노출하게 한다(스펙 §6.2 "조기 전환·테스트 깨짐 즉시 노출"). 신규 도메인 테이블은 Plan 2~4에서 per-domain 마이그레이션으로 추가.

**Files:**
- Create (디렉터리): `src/main/resources/db/migration/` (+ 임시 자리표시 `.gitkeep` — 생성 후 제거)
- Create (임시, Step 4에서 삭제): `src/test/kotlin/com/komsco/voucher/support/SchemaExportTest.kt`
- Create (생성물): `src/main/resources/db/migration/V1__baseline.sql`
- Modify: `src/main/resources/application.yml` (7~20행: `jpa.hibernate.ddl-auto`, `flyway`)
- Modify: `src/test/resources/application-test.yml` (1~6행 전체)

**Interfaces:**
- Consumes (locked contract): 모든 JPA 엔티티(`LedgerEntry`, `Voucher`, `Member`, `Transaction`, `Merchant`, `Region`, `Settlement`, audit/idempotency 엔티티 등) + `BaseEntity`(`@Version version`, `createdAt`, `updatedAt`). `build.gradle.kts`에 `flyway-core` + `flyway-mysql` 이미 존재(52~53행).
- Produces: `classpath:db/migration/V1__baseline.sql`(Flyway가 빈 스키마에 적용). 메인/테스트 부팅이 `flyway 마이그레이션 → Hibernate validate` 순서로 동작.

- [ ] **Step 1: 마이그레이션 디렉터리 생성**
  ```
  mkdir -p src/main/resources/db/migration
  ```
  (Hibernate script writer는 부모 디렉터리를 만들지 않으므로 선생성 필수.)

- [ ] **Step 2: 일회성 스키마 export 테스트 작성** — `src/test/kotlin/com/komsco/voucher/support/SchemaExportTest.kt`. `IntegrationTestSupport`(MySQL+Redis 컨테이너 재사용)를 상속하되, 자체 `@DynamicPropertySource`로 (1) `ddl-auto=none`, (2) flyway 비활성(아직 V1 없음), (3) jakarta 스키마 export 스크립트, (4) **statement 구분자 `;`**(미설정 시 Flyway가 파일 전체를 1개 statement로 보고 실패)를 주입한다.
  ```kotlin
  package com.commerce.support

  import org.junit.jupiter.api.Test
  import org.springframework.test.context.DynamicPropertyRegistry
  import org.springframework.test.context.DynamicPropertySource

  /**
   * 일회성: 현재 Hibernate 스키마를 MySQL 방언 DDL로 export 해 V1__baseline.sql 생성.
   * Testcontainers MySQL에 연결되어 방언이 MySQL로 자동 감지된다.
   * 생성 확인 후 이 파일은 삭제한다(Task 2 Step 4).
   */
  class SchemaExportTest : IntegrationTestSupport() {

      @Test
      fun `export current Hibernate schema to V1 baseline`() {
          // 컨텍스트 로드 시 Hibernate가 EMF 초기화 중 스크립트를 파일로 기록한다.
          // 별도 단언 불필요 — 파일 생성 자체가 산출물.
      }

      companion object {
          @JvmStatic
          @DynamicPropertySource
          fun schemaGenProperties(registry: DynamicPropertyRegistry) {
              registry.add("spring.jpa.hibernate.ddl-auto") { "none" }
              registry.add("spring.flyway.enabled") { "false" }
              registry.add("spring.jpa.properties.jakarta.persistence.schema-generation.scripts.action") { "create" }
              registry.add("spring.jpa.properties.jakarta.persistence.schema-generation.scripts.create-target") {
                  "src/main/resources/db/migration/V1__baseline.sql"
              }
              registry.add("spring.jpa.properties.hibernate.hbm2ddl.delimiter") { ";" }
          }
      }
  }
  ```

- [ ] **Step 3: export 실행 → 베이스라인 생성** — Docker 데몬 필요.
  ```
  ./gradlew test --tests "com.commerce.support.SchemaExportTest"
  ```
  기대: **PASS**. 실행 후 `src/main/resources/db/migration/V1__baseline.sql`이 생성되고, 각 DDL이 `;`로 끝나며 `create table ...`(+ FK `alter table ... add constraint ...`)만 포함(`drop` 없음, `action=create`이므로).
  생성 확인:
  ```
  ./gradlew :help -q >/dev/null; test -s src/main/resources/db/migration/V1__baseline.sql && echo "BASELINE OK" || echo "BASELINE MISSING"
  ```
  기대 출력: `BASELINE OK`. (`ledger_entries`, `vouchers`/`voucher`, `members`/`member`, `transactions` 등 모든 테이블 정의 포함 여부를 파일에서 눈으로 확인.)

- [ ] **Step 4: export 테스트 + 자리표시 제거** — 베이스라인 확보 후 일회성 테스트를 삭제(다음 단계에서 flyway 활성화 시 이 테스트는 V1을 재생성/충돌시키므로 반드시 제거). `.gitkeep`이 있으면 제거.
  ```
  rm -f src/test/kotlin/com/komsco/voucher/support/SchemaExportTest.kt
  rm -f src/main/resources/db/migration/.gitkeep
  ```

- [ ] **Step 5: 메인 프로파일 전환 (`application.yml`)** — `jpa.hibernate.ddl-auto`를 `update→validate`로, `flyway.enabled`를 `false→true`로, `baseline-on-migrate: true`(기존 dev DB가 비어있지 않아도 안전) 추가. 7~20행을 다음으로 교체:
  ```yaml
    jpa:
      hibernate:
        ddl-auto: validate
      properties:
        hibernate:
          dialect: org.hibernate.dialect.MySQLDialect
          format_sql: true
      open-in-view: false
    data:
      redis:
        host: localhost
        port: 6379
    flyway:
      enabled: true
      baseline-on-migrate: true
  ```
  (datasource 블록 1~6행 및 server/management 블록은 변경하지 않는다.)

- [ ] **Step 6: 테스트 프로파일 전환 (`application-test.yml`)** — 베이스라인을 실제로 통합 테스트가 검증하도록 테스트도 `validate + flyway`로 전환(create-drop 폐기). 파일 전체를 다음으로 교체:
  ```yaml
  spring:
    jpa:
      hibernate:
        ddl-auto: validate
    flyway:
      enabled: true
      baseline-on-migrate: true
  ```

- [ ] **Step 7: 전체 통합 부팅/검증 — E2E 통합 테스트로 "앱 부팅 + validate 통과 + 기존 테스트 PASS" 증명** — 빈 Testcontainers MySQL에 Flyway가 `V1`을 적용하고 Hibernate가 `validate`로 엔티티 일치를 확인한 뒤 시나리오가 통과해야 한다. Docker 데몬 필요.
  ```
  ./gradlew test --tests "com.commerce.integration.E2EFlowTest" --tests "com.commerce.ledger.application.LedgerServiceTest"
  ```
  기대: **PASS** (전체 컨텍스트가 flyway→validate 경로로 부팅됨 = "앱 부팅" 검증). 만약 `SchemaValidationException`(예: 컬럼 타입/기본값/길이 불일치)이 나면 **베이스라인 결함**이므로: (a) Step 2 export 테스트를 임시 복구해 `V1` 재생성하거나, (b) `V1__baseline.sql`의 해당 컬럼 정의를 엔티티에 맞게 수정한다. **`ddl-auto`를 되돌리지 않는다**(스펙 §6.2 의도: 불일치 즉시 노출·수정).

- [ ] **Step 8: 전체 회귀 실행(선택, 권장)** — 다른 통합/동시성 테스트도 새 부팅 경로에서 통과하는지 확인.
  ```
  ./gradlew test
  ```
  기대: **PASS** (기존 단위/통합/동시성 테스트 + Task 1 단위 테스트). Docker 데몬 필요.

- [ ] **Step 9: 커밋**
  ```
  git add src/main/resources/db/migration/V1__baseline.sql \
          src/main/resources/application.yml \
          src/test/resources/application-test.yml
  git rm --cached --ignore-unmatch src/test/kotlin/com/komsco/voucher/support/SchemaExportTest.kt
  git add -A src/test/kotlin/com/komsco/voucher/support/
  git commit -m "build(flyway): 현재 스키마 V1 베이스라인 + ddl-auto validate/flyway 전환 (메인·테스트 프로파일)"
  ```

---

### Task 3: JWT 인증 필터 + principal 헬퍼 + 보호 엔드포인트 (스펙 §6.1)

> MUST: `OncePerRequestFilter`로 `Authorization: Bearer` 검증 → `SecurityContextHolder`에 인증 주입. 신규 컨트롤러는 body의 memberId 대신 `SecurityUtils.currentMemberId()`로 신원 도출. 미인증 시 401을 반환하도록 `HttpStatusEntryPoint(UNAUTHORIZED)` 설정. 기존 voucher/settlement 엔드포인트 retrofit·가맹점 인증은 **STRETCH**(아래 Notes에 문서화).

**Files:**
- Create: `src/main/kotlin/com/komsco/voucher/config/JwtAuthenticationFilter.kt`
- Create: `src/main/kotlin/com/komsco/voucher/common/security/SecurityUtils.kt`
- Create: `src/main/kotlin/com/komsco/voucher/member/interfaces/AuthController.kt`
- Modify: `src/main/kotlin/com/komsco/voucher/config/SecurityConfig.kt` (전체 — 필터 주입 + entry point + `/api/v1/me` authenticated)
- Modify: `src/main/kotlin/com/komsco/voucher/common/exception/ErrorCode.kt` (Common 섹션에 `UNAUTHORIZED` 추가)
- Create: `src/test/kotlin/com/komsco/voucher/integration/JwtAuthenticationFilterTest.kt`

**Interfaces:**
- Consumes (locked contract): `JwtTokenProvider.generateToken(memberId: Long, role: String): String`, `validateToken(token): Boolean`, `getMemberIdFromToken(token): Long`, `getRoleFromToken(token): String`. `BusinessException(ErrorCode)`. `IntegrationTestSupport`.
- Produces:
  - `class JwtAuthenticationFilter(jwtTokenProvider: JwtTokenProvider) : OncePerRequestFilter` — Bearer 토큰 검증 후 `UsernamePasswordAuthenticationToken(principal = memberId: Long, credentials = null, authorities = [SimpleGrantedAuthority("ROLE_"+role)])` 주입.
  - `object SecurityUtils { fun currentMemberId(): Long; fun currentMemberIdOrNull(): Long? }` — Plan 2~4 컨트롤러가 신원 도출에 사용.
  - `GET /api/v1/me` → `MeResponse(memberId: Long)` (보호 엔드포인트; 필터/헬퍼 동작 레퍼런스).
  - `ErrorCode.UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "인증이 필요합니다")`.

- [ ] **Step 1: 실패 통합 테스트 작성** — `src/test/kotlin/com/komsco/voucher/integration/JwtAuthenticationFilterTest.kt`. `@AutoConfigureMockMvc`로 보안 필터 체인을 MockMvc에 적용(Spring Boot가 security 필터를 자동 적용). 토큰은 주입된 `JwtTokenProvider`로 직접 생성(회원 DB 불필요 — 필터는 서명만 검증).
  ```kotlin
  package com.commerce.integration

  import com.commerce.config.JwtTokenProvider
  import com.commerce.support.IntegrationTestSupport
  import org.junit.jupiter.api.Test
  import org.springframework.beans.factory.annotation.Autowired
  import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
  import org.springframework.test.web.servlet.MockMvc
  import org.springframework.test.web.servlet.get

  @AutoConfigureMockMvc
  class JwtAuthenticationFilterTest : IntegrationTestSupport() {

      @Autowired lateinit var mockMvc: MockMvc
      @Autowired lateinit var jwtTokenProvider: JwtTokenProvider

      @Test
      fun `protected endpoint returns 401 without a token`() {
          mockMvc.get("/api/v1/me")
              .andExpect { status { isUnauthorized() } }
      }

      @Test
      fun `protected endpoint returns 200 and resolves memberId from a valid token`() {
          val token = jwtTokenProvider.generateToken(777L, "USER")
          mockMvc.get("/api/v1/me") {
              header("Authorization", "Bearer $token")
          }.andExpect {
              status { isOk() }
              jsonPath("$.memberId") { value(777) }
          }
      }

      @Test
      fun `protected endpoint returns 401 with a malformed token`() {
          mockMvc.get("/api/v1/me") {
              header("Authorization", "Bearer not-a-real-jwt")
          }.andExpect { status { isUnauthorized() } }
      }
  }
  ```

- [ ] **Step 2: 테스트 실행 → 컴파일/런타임 실패 확인(빨강)** — `/api/v1/me` 미존재 + 보호 규칙 미설정. Docker 데몬 필요.
  ```
  ./gradlew test --tests "com.commerce.integration.JwtAuthenticationFilterTest"
  ```
  기대: **FAIL** — 현재 `SecurityConfig`가 `anyRequest().permitAll()`이고 `/api/v1/me` 핸들러가 없어 401 대신 404가 반환되어 단언 실패.

- [ ] **Step 3: `ErrorCode`에 `UNAUTHORIZED` 추가** — `src/main/kotlin/com/komsco/voucher/common/exception/ErrorCode.kt`의 Common 섹션, `IDEMPOTENCY_DUPLICATE(...)` 줄 다음에 한 줄 추가:
  ```kotlin
      IDEMPOTENCY_DUPLICATE(HttpStatus.OK, "이미 처리된 요청입니다"),
      UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "인증이 필요합니다"),
  ```

- [ ] **Step 4: `SecurityUtils` 헬퍼 작성** — `src/main/kotlin/com/komsco/voucher/common/security/SecurityUtils.kt`. 필터가 주입한 principal(`Long`)을 읽는다. 미인증/타입 불일치 시 `UNAUTHORIZED`.
  ```kotlin
  package com.commerce.common.security

  import com.commerce.common.exception.BusinessException
  import com.commerce.common.exception.ErrorCode
  import org.springframework.security.core.context.SecurityContextHolder

  /**
   * 신규 엔드포인트(Plan 2~4)는 요청 body의 memberId를 신뢰하지 않고
   * 인증 principal에서 신원을 도출한다. principal은 JwtAuthenticationFilter가
   * 세팅한 회원 ID(Long)이다.
   */
  object SecurityUtils {

      fun currentMemberId(): Long {
          val principal = SecurityContextHolder.getContext().authentication?.principal
          if (principal !is Long) throw BusinessException(ErrorCode.UNAUTHORIZED)
          return principal
      }

      fun currentMemberIdOrNull(): Long? =
          SecurityContextHolder.getContext().authentication?.principal as? Long
  }
  ```

- [ ] **Step 5: `JwtAuthenticationFilter` 작성** — `src/main/kotlin/com/komsco/voucher/config/JwtAuthenticationFilter.kt`. locked contract의 principal/authorities/주입 형태를 글자 그대로 따른다.
  ```kotlin
  package com.commerce.config

  import jakarta.servlet.FilterChain
  import jakarta.servlet.http.HttpServletRequest
  import jakarta.servlet.http.HttpServletResponse
  import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
  import org.springframework.security.core.authority.SimpleGrantedAuthority
  import org.springframework.security.core.context.SecurityContextHolder
  import org.springframework.security.web.authentication.WebAuthenticationDetailsSource
  import org.springframework.stereotype.Component
  import org.springframework.web.filter.OncePerRequestFilter

  /**
   * Authorization: Bearer <jwt> 헤더를 읽어 검증하고,
   * SecurityContextHolder에 principal=memberId(Long), 권한=ROLE_<role> 인증을 주입한다.
   * 토큰이 없거나 유효하지 않으면 컨텍스트를 비워두어(익명) 보호 엔드포인트에서 401이 되게 한다.
   */
  @Component
  class JwtAuthenticationFilter(
      private val jwtTokenProvider: JwtTokenProvider,
  ) : OncePerRequestFilter() {

      override fun doFilterInternal(
          request: HttpServletRequest,
          response: HttpServletResponse,
          filterChain: FilterChain,
      ) {
          val header = request.getHeader("Authorization")
          if (header != null && header.startsWith(BEARER_PREFIX)) {
              val token = header.substring(BEARER_PREFIX.length)
              if (jwtTokenProvider.validateToken(token)) {
                  val memberId: Long = jwtTokenProvider.getMemberIdFromToken(token)
                  val role: String = jwtTokenProvider.getRoleFromToken(token)
                  val authentication = UsernamePasswordAuthenticationToken(
                      memberId,
                      null,
                      listOf(SimpleGrantedAuthority("ROLE_$role")),
                  )
                  authentication.details = WebAuthenticationDetailsSource().buildDetails(request)
                  SecurityContextHolder.getContext().authentication = authentication
              }
          }
          filterChain.doFilter(request, response)
      }

      companion object {
          private const val BEARER_PREFIX = "Bearer "
      }
  }
  ```

- [ ] **Step 6: `SecurityConfig` 전환** — 필터 주입 + 401 entry point + `/api/v1/me` 보호. 기존 endpoint들은 retrofit하지 않으므로 `anyRequest().permitAll()` 유지(STRETCH 분리). 파일 전체를 다음으로 교체:
  ```kotlin
  package com.commerce.config

  import org.springframework.context.annotation.Bean
  import org.springframework.context.annotation.Configuration
  import org.springframework.http.HttpStatus
  import org.springframework.security.config.annotation.web.builders.HttpSecurity
  import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
  import org.springframework.security.config.http.SessionCreationPolicy
  import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
  import org.springframework.security.crypto.password.PasswordEncoder
  import org.springframework.security.web.SecurityFilterChain
  import org.springframework.security.web.authentication.HttpStatusEntryPoint
  import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter

  @Configuration
  @EnableWebSecurity
  class SecurityConfig(
      private val jwtAuthenticationFilter: JwtAuthenticationFilter,
  ) {

      @Bean
      fun securityFilterChain(http: HttpSecurity): SecurityFilterChain {
          http
              .csrf { it.disable() }
              .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
              .authorizeHttpRequests {
                  it.requestMatchers("/api/v1/members/register", "/api/v1/members/login").permitAll()
                  it.requestMatchers("/actuator/**", "/swagger-ui/**", "/v3/api-docs/**").permitAll()
                  // 신규 보호 엔드포인트(인증 principal 기반). 구체 매처가 anyRequest보다 먼저 평가됨.
                  it.requestMatchers("/api/v1/me").authenticated()
                  // 기존 voucher/settlement 엔드포인트 retrofit은 STRETCH → 현 단계 전체 허용 유지.
                  it.anyRequest().permitAll()
              }
              // 미인증 접근 시 403 대신 401 반환.
              .exceptionHandling { it.authenticationEntryPoint(HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)) }
              .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter::class.java)

          return http.build()
      }

      @Bean
      fun passwordEncoder(): PasswordEncoder = BCryptPasswordEncoder()
  }
  ```

- [ ] **Step 7: `AuthController`(/api/v1/me) 작성** — `src/main/kotlin/com/komsco/voucher/member/interfaces/AuthController.kt`. 신원은 body가 아닌 `SecurityUtils.currentMemberId()`에서 도출(Plan 2~4 패턴의 레퍼런스).
  ```kotlin
  package com.commerce.member.interfaces

  import com.commerce.common.security.SecurityUtils
  import org.springframework.web.bind.annotation.GetMapping
  import org.springframework.web.bind.annotation.RequestMapping
  import org.springframework.web.bind.annotation.RestController

  @RestController
  @RequestMapping("/api/v1")
  class AuthController {

      /** 현재 인증된 회원 신원 확인용 엔드포인트. JWT 필터/principal 헬퍼 동작의 레퍼런스. */
      @GetMapping("/me")
      fun me(): MeResponse = MeResponse(memberId = SecurityUtils.currentMemberId())
  }

  data class MeResponse(val memberId: Long)
  ```

- [ ] **Step 8: 테스트 재실행 → 통과(초록)** — Docker 데몬 필요.
  ```
  ./gradlew test --tests "com.commerce.integration.JwtAuthenticationFilterTest"
  ```
  기대: **PASS** — 토큰 없음/오류 토큰 → 401, 유효 토큰 → 200 + `memberId=777`.

- [ ] **Step 9: 회귀 — 기존 인증/통합 테스트 영향 없음 확인** — `anyRequest().permitAll()` 유지 + 신규 매처만 추가했으므로 기존 흐름은 불변. 대표 통합 테스트 + Task 1 단위 테스트 동시 실행. Docker 데몬 필요.
  ```
  ./gradlew test --tests "com.commerce.integration.E2EFlowTest" --tests "com.commerce.ledger.domain.AccountCodeAndEntryTypeTest"
  ```
  기대: **PASS**.

- [ ] **Step 10: 커밋**
  ```
  git add src/main/kotlin/com/komsco/voucher/config/JwtAuthenticationFilter.kt \
          src/main/kotlin/com/komsco/voucher/config/SecurityConfig.kt \
          src/main/kotlin/com/komsco/voucher/common/security/SecurityUtils.kt \
          src/main/kotlin/com/komsco/voucher/member/interfaces/AuthController.kt \
          src/main/kotlin/com/komsco/voucher/common/exception/ErrorCode.kt \
          src/test/kotlin/com/komsco/voucher/integration/JwtAuthenticationFilterTest.kt
  git commit -m "feat(auth): JWT 인증 필터 + currentMemberId 헬퍼 + /api/v1/me 보호 엔드포인트 (신규 엔드포인트 principal 기반 신원)"
  ```

---

## Done criteria (Plan 1 전체)
- [ ] `AccountCode`에 `PROMOTION_FUNDING`/`POINT_BALANCE`/`POINT_FUNDING`, `LedgerEntryType`에 `COUPON_SUBSIDY`/`POINT_EARN` 존재 + 단위 테스트 PASS.
- [ ] `V1__baseline.sql` 존재, 메인·테스트 모두 `ddl-auto: validate` + `flyway.enabled: true`로 부팅되고 `E2EFlowTest`/`LedgerServiceTest` PASS.
- [ ] `GET /api/v1/me`가 토큰 없으면 401, 유효 토큰이면 200 + principal memberId 반환. `SecurityUtils.currentMemberId()`가 Plan 2~4에서 재사용 가능.
- [ ] `./gradlew test` 전체 PASS(Docker 데몬 가동 시).

## Notes / 가정 / STRETCH 문서화
- **가정 1 (테스트 프로파일 동반 전환)**: 스펙(B)는 메인 `application.yml`의 `validate/flyway` 전환을 지시하지만, 스펙 §6.2의 "테스트 깨짐 즉시 노출" 의도를 살리려 `application-test.yml`도 `validate + flyway`로 함께 전환했다(create-drop 폐기). 이로써 베이스라인이 Testcontainers 통합 테스트에서 실제로 검증된다.
- **가정 2 (스키마 export 방식)**: 로컬 `mysqldump` PATH 의존을 피하려고, 이미 존재하는 Testcontainers MySQL에 붙는 일회성 `SchemaExportTest` + `jakarta.persistence.schema-generation.scripts.*`로 MySQL 방언 DDL을 생성했다. `hibernate.hbm2ddl.delimiter=";"`를 반드시 설정해 Flyway가 statement를 분리할 수 있게 했다. 생성 후 테스트는 삭제한다.
- **가정 3 (baseline-on-migrate)**: 기존 dev DB(localhost, ddl-auto:update로 누적된 스키마)가 비어있지 않아도 `baseline-on-migrate: true`로 부팅이 깨지지 않게 했다. 빈 스키마(Testcontainers/CI)에서는 `V1`이 정상 적용된다.
- **가정 4 (401 entry point)**: Spring Security 6 기본 entry point는 미인증 시 403을 반환하므로, 스펙이 요구한 "토큰 없으면 401"을 위해 `HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)`를 명시 설정했다.
- **STRETCH (이 계획 범위 밖, 문서화만)**:
  - **가맹점 인증**: 가맹점은 `Member`/`MemberRole`과 별개 엔티티(`merchant` 패키지)다. redeem/정산의 인증 주체(가맹점 토큰·역할 모델)는 본 계획에서 다루지 않으며, 현재 principal은 **회원 신원 전용**이다. 가맹점 인증 풀 구현은 STRETCH.
  - **기존 엔드포인트 retrofit**: voucher/settlement/transaction 등 기존 컨트롤러를 `authenticated()`로 전환하고 DTO에서 body memberId/merchantId를 제거하는 cross-cutting 변경은 STRETCH(`anyRequest().permitAll()` 유지로 분리). 신규 엔드포인트(Plan 2~4)만 처음부터 principal 기반으로 안전하게 만든다.
- **베이스라인 검증 실패 시 대응(스펙 §6.2)**: Task 2 Step 7에서 `validate` 불일치가 나면 `ddl-auto`를 되돌리지 말고 `V1__baseline.sql`을 엔티티에 맞게 수정(또는 export 재실행)한다. 이것이 조기 전환의 목적(불일치 즉시 노출·교정)이다.
