# Plan 0 — REPOSITIONING (문서/용어 — 커머스 재포지셔닝)

> 베이스 스펙: `docs/superpowers/specs/2026-06-29-commerce-repositioning-design.md` (§1 배경·목표, §2 전략, §10 JD 매핑, §11.1 MUST 1).
> 이 계획은 **MUST item 1 "재포지셔닝(문서/용어)"** 만 다룬다. **가장 먼저(Day 0/1) 실행**하여 이후 Plan 1~5(토대·쿠폰·포인트·AI·컨테이너)가 일관된 커머스 서사·명명 위에서 쓰이도록 한다.
> 분량: 약 0.5~1일.

> **선행 완료 사실(중요)**: **코드 패키지 리네임은 이미 완료**되었다 — `com.komsco.voucher` → **`com.commerce`** (디렉터리 `src/{main,test}/kotlin/com/commerce/`, `build.gradle.kts` `group = "com.commerce"`, 전 `.kt` package/import, Plan 1~5 문서 포함). 따라서 본 계획의 "회사명 제거"는 **문서 산문 + 문서 내 코드 스니펫**만 남았다. 코드/패키지는 다시 건드리지 않는다.

> **명명 플레이스홀더(git 회사명 미노출 정책)**: 본 문서는 공개 git 리포에 커밋되므로 **실제 기관/회사명을 적지 않는다**. 아래 토큰을 쓰고 실행자가 grep·치환 시 실제 명칭으로 대입한다.
> - `<ORG>` = 발신 기관의 **영문 대문자 브랜드**, `<ORG_KR>` = 그 기관의 **한글 정식명/약칭**. (타깃 고용주명은 본 문서·전 계획 어디에도 없다.)

## Goal
프로젝트의 **외부 서사(README·docs 산문)** 를 "공공 지역상품권 포트폴리오"에서 **커머스 백엔드**로 재포지셔닝하고, 문서에 남은 회사명·옛 패키지 참조를 정리한다.
- **(A) README 한 줄 피치 + 인트로 재작성**: 스펙 §1.1의 커머스 한 줄 정의로 교체, 도입부를 커머스 3축(**결제·정산·쿠폰·포인트 신뢰성 + 대용량 동시성 + AI 프로모션 자동화**)으로 재구성. (스펙 §1.1, §1.2, §10)
- **(B) 도메인 용어 프레이밍 전환**: README/docs 산문에서 **발행→구매/주문 맥락**, **사용처→가맹점**으로 프레이밍, **정산은 유지**. (스펙 §1.2 자산 매핑 표)
- **(C) 커머스 지향 면접 준비 문서 추가**: 커머스 JD(결제·정산·쿠폰·포인트·AI·대용량) Q&A. (스펙 §1.4, §5.3, §10)
- **(D) 문서 정리(컴플라이언스 + 명명 동기화)**: README/docs 01~06 **산문**에서 회사·기관명을 제거하고, 문서 내 **코드 스니펫/구조도의 옛 패키지 참조 `com.komsco.voucher`/`com/komsco/voucher/` 를 `com.commerce`/`com/commerce/` 로 동기화**(코드는 이미 리네임됨). (사용자 정책: git 공개 리포에 회사명 미노출)

## Architecture
- 편집 대상은 **마크다운 산문/스니펫만**: `README.md`, `docs/01-domain-design.md` ~ `docs/06-financial-concepts.md`. 신규 면접 문서 1개(`docs/07-commerce-interview-prep.md`).
- **코드 무변경 경계**: `src/**`, `build.gradle.kts`, 패키지 선언/ import 는 **이미 `com.commerce` 로 리네임 완료**되었으므로 본 계획에서 다시 바꾸지 않는다(문서만 동기화).
- **두 종류의 잔여 토큰 구분**:
  - **산문 회사명**(스크럽 대상): 영문 대문자 브랜드(`<ORG>`) 또는 한글 기관명(`<ORG_KR>`).
  - **문서 내 옛 패키지 참조**(동기화 대상): 소문자 `com.komsco.voucher` / 구조도 경로 `com/komsco/voucher/` → `com.commerce` / `com/commerce/`.
  - 둘은 대소문자/형태가 달라 grep으로 안전히 분리된다.
- **도메인 용어 정책**: `지역사랑상품권`/`상품권`/`바우처`는 회사명이 아니라 **도메인 제품 용어**다. 스크럽 대상이 아니다. 다만 피치/인트로에서는 커머스 일반어(주문·결제·쿠폰·포인트)를 전면에 두고, 상품권은 "선불 바우처(결제수단의 한 형태)"로 위치시킨다.

## Tech Stack
- 도구: 텍스트 편집 + `grep`(ripgrep). 빌드/테스트 무관.
- 검증 명령(리포 루트 Git Bash; `<ORG>`/`<ORG_KR>`는 실행자가 실제 명칭으로 치환):
  - **범위**: 산문 파일 집합 `README.md docs/0[1-7]-*.md` 에 한정(`docs/superpowers/` 제외 — 플랜이 토큰을 인용하므로).
  - **회사명 스크럽 검증**: `grep -rnE "<ORG>|<ORG_KR>" README.md docs/0[1-7]-*.md` → **0 hits**.
  - **옛 패키지 참조 동기화 검증**: `grep -rn "com\.komsco" README.md docs/0[1-7]-*.md` → **0 hits**(모두 `com.commerce`로 치환됨).
  - **피치 일치**: `grep -nF "결제·정산·쿠폰·포인트의 재무 정합성" README.md` 가 스펙 §1.1과 일치.
- 시작 기준선(검증용): 산문 회사명(대문자/한글) hits ≈ **24건**(`docs/02`=2, `docs/03`=18, `docs/04`=1, `docs/05`=1, `docs/06`=2). 옛 패키지 스니펫 참조 `com.komsco.voucher` 는 별도로 다수(특히 `docs/04`) — 동기화 대상.

## Global Constraints
- **코드 재변경 금지**: 패키지는 이미 `com.commerce` 로 리네임 완료. 본 계획은 **문서만** 수정한다. 각 태스크 종료 시 `git status` 로 `src/`·`build.gradle.kts` 무변경 확인.
- **사실 보존**: 재포지셔닝은 *프레이밍* 전환이지 *허위 기능 추가*가 아니다. 미구현(쿠폰·포인트·AI는 Plan 2~4) 은 "계획/로드맵" 또는 미래시제로 표기(스펙 §10의 "현재 → 계획 후" 톤).
- **도메인 용어 보존**: `상품권`/`바우처`/`지역사랑상품권` 은 스크럽 대상 아님.
- **각 태스크 = 명시적 편집 대상 + 검증 + 커밋**(커밋 프리픽스 `docs:`).
- **TDD 아님**(코드 테스트 없음). "검증"은 grep/육안 diff.

---

### Task A: README 한 줄 피치 + 인트로를 커머스 서사로 재작성 (스펙 §1.1, §1.2, §10)

**Files:** Modify: `README.md` (1~53행 — 제목·한 줄 설명·"설계 원칙" 표·"시스템 아키텍처" 도입부)

- [ ] **Step 1: 제목 + 한 줄 피치 교체** — `# 모바일 상품권 관리 시스템` + "지역사랑상품권의 발행 → 유통 → 정산 …"(1~4행)을 스펙 §1.1로 교체:
  - 제목 예: `# 커머스 결제·프로모션 백엔드`
  - 한 줄 피치(§1.1): `대용량 트래픽에서 결제·정산·쿠폰·포인트의 재무 정합성을 보장하고, AI로 프로모션 운영을 자동화하는 커머스 백엔드.`
- [ ] **Step 2: 인트로를 커머스 3축으로 재구성** — (1) 신뢰성(복식부기 원장·보상 트랜잭션·멱등성), (2) 대용량 동시성(Redisson 분산락 + DB 비관적 락 + Redis Lua 예산 카운터 + k6), (3) AI 프로모션 자동화(자연어→초안, 결정적 가드레일; *로드맵* 표기). 미구현은 "로드맵"으로 명시.
- [ ] **Step 3: "설계 원칙" 표 프레이밍 보강** — 3원칙(재무 무결성·감사 추적성·동시성 안전) 유지 + "커머스에서의 의미" 한 칸씩 추가(스펙 §1.2 표).

**Verification:** `grep -nF "결제·정산·쿠폰·포인트의 재무 정합성" README.md` → 1 hit; `grep -nE "발행 → 유통 → 정산 전 생애주기" README.md` → 0 hits.

**Commit:** `docs: README 피치·인트로를 커머스 서사로 재포지셔닝`

---

### Task B: 도메인 용어 프레이밍을 커머스로 전환 (스펙 §1.2)

**Files:** Modify: `README.md`(핵심 흐름·구현 기능 상세), `docs/01-domain-design.md`, `docs/02-architecture-decisions.md`, `docs/03-implementation-roadmap.md`(산문)

- [ ] **Step 1: 발행 → 구매/주문 맥락** — "발행"을 고객 관점 "구매/주문"으로 서술. **클래스/엔드포인트명(`VoucherIssueService`, `purchase`)은 그대로 인용**(코드 불변, 단 패키지 표기는 `com.commerce`).
- [ ] **Step 2: 사용처 → 가맹점 통일** — "사용처/이용처"를 "가맹점(merchant)"으로 통일.
- [ ] **Step 3: 정산 유지** — "정산(settlement)"은 유지하되 커머스 PG/마켓플레이스 정산과 동형임을 한 줄 보강.
- [ ] **Step 4: 코드 무변경 확인** — `git diff --stat -- src build.gradle.kts` 가 비어 있음.

**Verification:** `git diff --stat -- src build.gradle.kts` 비어 있음; 육안으로 "발행→구매/주문", "사용처→가맹점" 반영.

**Commit:** `docs: 도메인 용어를 커머스로 프레이밍(발행→구매/주문, 사용처→가맹점, 정산 유지)`

---

### Task C: 커머스 지향 면접 준비 문서 추가 (스펙 §1.4, §5.3, §10)

**Files:** Create: `docs/07-commerce-interview-prep.md`; (옵션) `docs/05-interview-preparation.md` 상단에 링크.

- [ ] **Step 1: 커머스 JD 매핑 섹션** — 스펙 §10 표를 Q&A로(도메인/대용량/데이터/AI 항목별 "현재 자산 → 어필 포인트").
- [ ] **Step 2: 핵심 예상 질문 10~15개** — ① 복식부기가 커머스 결제·정산에서 왜 강점인가, ② 쿠폰 할인을 2-leg 2쌍으로 분개한 이유(§3.2·§4.1), ③ 예산 상한 Redis Lua 원자 제어 + DB 재동기화(§4.1), ④ 결합결제 락 순서·데드락 회피(§4.3), ⑤ 포인트 `POINT_BALANCE` 차변정상 모델링(§4.2), ⑥ AI 어시스턴트 결정적 가드레일·프롬프트 인젝션 차단(§5), ⑦ 멱등성 exactly-once(§7), ⑧ 대용량 동시성 증명(k6·동시성 테스트). 미구현은 "계획"으로 명시.
- [ ] **Step 3: AI 세션 아티팩트 요지**(§5.3) — 모델 선택·출력 스키마·가드레일·골든셋/모킹 계약 테스트 요약.
- [ ] **Step 4: 회사명·옛 패키지 미포함 확인** — 신규 문서는 처음부터 클린(회사명 0, `com.komsco` 0).

**Verification:** `test -f docs/07-commerce-interview-prep.md`; `grep -rnE "<ORG>|<ORG_KR>" docs/07-commerce-interview-prep.md` → 0; `grep -rn "com\.komsco" docs/07-commerce-interview-prep.md` → 0.

**Commit:** `docs: 커머스 JD 대비 면접 준비 문서(07) 추가`

---

### Task D: 회사명 스크럽 + 문서 코드 스니펫을 com.commerce로 동기화 (사용자 정책)

**Files:** Modify: `README.md`, `docs/02-architecture-decisions.md`, `docs/03-implementation-roadmap.md`, `docs/04-implementation-plan.md`(스니펫 다수), `docs/05-interview-preparation.md`, `docs/06-financial-concepts.md`.

- [ ] **Step 1: 베이스라인 측정** — `grep -rnE "<ORG>|<ORG_KR>" README.md docs/0[1-7]-*.md`(실행자 치환) + `grep -rn "com\.komsco" README.md docs/0[1-7]-*.md` 로 현재 위치 확인.
- [ ] **Step 2: 산문 회사명 치환** — 도메인 중립어로:
  - `<ORG> relevance` / `★ <ORG> 관련 역량…` → `commerce relevance` / `★ 핵심 도메인 역량…`.
  - `<ORG>에 중요한가`, `<ORG> 컴플라이언스` → `이 시스템에 중요한가`, `커머스/금융 컴플라이언스`.
  - `<ORG> 포트폴리오로 증명` → `커머스 백엔드 포트폴리오로 증명`.
  - `<ORG> 모바일 상품권 시스템`(제목) → `커머스 결제·프로모션 백엔드`.
  - `발행기관 (<ORG_KR>/지자체)` → `발행기관 (선불 바우처 발행처)`.
- [ ] **Step 3: 옛 패키지 참조 동기화** — 문서 내 `com.komsco.voucher` → `com.commerce`, 구조도 경로 `com/komsco/voucher/` → `com/commerce/`(코드는 이미 리네임됨; 문서 스니펫만 맞춘다). 케이스: `sed -i 's#com\.komsco\.voucher#com.commerce#g; s#com/komsco/voucher#com/commerce#g; s#com\.komsco#com.commerce#g'` 를 해당 doc 파일에 적용 후 육안 확인.
- [ ] **Step 4: 리네임 완료 기록** — `docs/03-implementation-roadmap.md` "후속 과제"(없으면 추가)에 한 줄: "패키지 `com.komsco.voucher` → `com.commerce` 리네임 완료(커머스 재포지셔닝)." (별도 보고서 파일 생성 금지)

**Verification:**
- `grep -rnE "<ORG>|<ORG_KR>" README.md docs/0[1-7]-*.md`(실행자 치환) → **0**.
- `grep -rn "com\.komsco" README.md docs/0[1-7]-*.md` → **0**.
- `git diff --stat -- src build.gradle.kts` 비어 있음(코드 무변경).

**Commit:** `docs: README·docs 산문 회사명 제거 + 문서 코드 스니펫을 com.commerce로 동기화`

---

## 완료 기준 (Definition of Done)
- README 피치가 스펙 §1.1과 일치, 인트로가 커머스 3축으로 구성.
- 산문 발행→구매/주문, 사용처→가맹점(정산 유지), **코드 무변경**.
- `docs/07-commerce-interview-prep.md` 가 커머스 JD 4축 커버.
- `grep -rnE "<ORG>|<ORG_KR>" README.md docs/0[1-7]-*.md`(실행자 치환) = **0**; `grep -rn "com\.komsco" README.md docs/0[1-7]-*.md` = **0**.
- 4개 커밋(A·B·C·D) 분리.

## 실행 순서 메모
- 본 Plan 0 은 **Day 0/1, 최우선**. (패키지 리네임은 이미 선행 완료.) Plan 1~5 이전에 머지하여 모든 후속 문서/PR이 커머스 서사·중립 명명 위에서 작성되게 한다(스펙 §11.2 Week 1 "(1) 재포지셔닝").
