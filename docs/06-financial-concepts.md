# 금융 개념 완전 정리 — 커머스 결제·프로모션 백엔드

> 이 프로젝트에서 사용된 **모든 금융/회계 개념**을 초보자도 완벽히 이해할 수 있도록 정리합니다.
> 각 개념에 대해 **일상생활 비유** → **정의** → **이 프로젝트의 코드** → **실무 맥락**을 설명합니다.

---

## 목차

1. [복식부기 (Double-Entry Bookkeeping)](#1-복식부기-double-entry-bookkeeping)
2. [원장 (Ledger)](#2-원장-ledger)
3. [계정과목 (Chart of Accounts)](#3-계정과목-chart-of-accounts)
4. [차변과 대변 (Debit & Credit)](#4-차변과-대변-debit--credit)
5. [보상 트랜잭션 (Compensating Transaction)](#5-보상-트랜잭션-compensating-transaction)
6. [정산 (Settlement)](#6-정산-settlement)
7. [청약철회 (Cooling-off Period)](#7-청약철회-cooling-off-period)
8. [잔액환불 (Partial Refund)](#8-잔액환불-partial-refund)
9. [멱등성 (Idempotency)](#9-멱등성-idempotency)
10. [가맹점 미수금 (Merchant Receivable)](#10-가맹점-미수금-merchant-receivable)
11. [만료 처리 (Expiration)](#11-만료-처리-expiration)
12. [감사 추적 (Audit Trail)](#12-감사-추적-audit-trail)
13. [불변식 (Invariant)](#13-불변식-invariant)
14. [분산 잠금 (Distributed Lock)](#14-분산-잠금-distributed-lock)
15. [할인율과 구매한도](#15-할인율과-구매한도)
16. [상품권 코드의 보안](#16-상품권-코드의-보안)
17. [원장 정합성 검증](#17-원장-정합성-검증)
18. [지역사랑상품권 운영 구조](#18-지역사랑상품권-운영-구조)
19. [금융 시스템 설계 원칙](#19-금융-시스템-설계-원칙)
20. [용어 사전](#20-용어-사전)

---

## 1. 복식부기 (Double-Entry Bookkeeping)

### 일상생활 비유

**가계부(단식부기):**
```
1월 1일: 월급 받음 +300만원
1월 5일: 마트 쇼핑 -5만원
1월 10일: 카페 -5천원

→ "돈이 들어오고 나간 것"만 기록
→ "돈이 어디서 와서 어디로 갔는지"는 모름
```

**회사 장부(복식부기):**
```
1월 1일: 
  은행 계좌 +300만원 (차변: 자산 증가)
  급여 수익 +300만원 (대변: 수익 발생)

1월 5일:
  식료품비 +5만원 (차변: 비용 발생)
  은행 계좌 -5만원 (대변: 자산 감소)

→ "돈의 출발지와 도착지" 양쪽 모두 기록!
→ 모든 기록의 왼쪽(차변) 합계 = 오른쪽(대변) 합계
```

### 정의

복식부기는 1494년 이탈리아 수학자 **루카 파치올리**가 체계화한 회계 방식입니다.

**핵심 원칙:**
> 모든 거래는 반드시 두 곳 이상에 기록되며, 차변 합계 = 대변 합계를 항상 유지한다.

**비유:** 
모든 돈에 GPS 추적기를 달아서, "어디서 출발했고 어디에 도착했는지" 기록하는 것.

### 왜 필요한가?

**단식부기의 문제:**
```
상품권 잔액: 50,000원 → 20,000원으로 변경됨

질문: "30,000원은 왜 줄었나요?"
답: "모릅니다. 그냥 줄었네요."

질문: "언제 줄었나요?"
답: "모릅니다. 현재 잔액만 알 수 있어요."

질문: "혹시 해커가 조작한 건 아닌가요?"
답: "확인할 방법이 없습니다."
```

**복식부기의 해결:**
```
원장 기록:
  2024-01-15 14:30:22
  거래 #7 (결제)
  차변: 가맹점_미수금 30,000원 (카페A에서 결제 수락)
  대변: 상품권_잔액  30,000원 (상품권 #1에서 차감)

답: "30,000원은 2024-01-15에 카페A에서 결제로 차감되었고,
     거래 #7에 의해 기록되었습니다."

검증: 전체 차변 합계 == 전체 대변 합계 → 조작 없음 확인!
```

### 이 프로젝트의 코드

```kotlin
// LedgerService.kt
@Service
class LedgerService(private val ledgerRepository: LedgerJpaRepository) {
    
    /**
     * 복식부기 기록: 반드시 2행(차변 1 + 대변 1)을 동시에 생성합니다.
     * 
     * 이 메서드는 @Transactional 내에서 동기 호출해야 합니다.
     * (잔액 변경과 원장 기록이 하나의 트랜잭션에서 원자적으로 처리)
     */
    fun record(
        debitAccount: AccountCode,    // 차변 계정 (자산 증가 쪽)
        creditAccount: AccountCode,   // 대변 계정 (자산 감소 쪽)
        amount: BigDecimal,           // 금액
        transactionId: Long,          // 어떤 거래에 의한 것인지
        entryType: LedgerEntryType    // 거래 유형 (PURCHASE, REDEMPTION 등)
    ): List<LedgerEntry> {
        
        // 차변 엔트리: "여기로 돈이 왔다"
        val debitEntry = LedgerEntry(
            account = debitAccount,
            side = LedgerEntrySide.DEBIT,
            amount = amount,
            transactionId = transactionId,
            entryType = entryType
        )
        
        // 대변 엔트리: "여기에서 돈이 나갔다"
        val creditEntry = LedgerEntry(
            account = creditAccount,
            side = LedgerEntrySide.CREDIT,
            amount = amount,
            transactionId = transactionId,
            entryType = entryType
        )
        
        // 반드시 2개를 동시에 저장! (하나만 저장되면 불균형 발생)
        return ledgerRepository.saveAll(listOf(debitEntry, creditEntry))
    }
}
```

### 실제 거래별 기록 예시

```
┌────────────────────────────────────────────────────────────────────┐
│ 거래: 50,000원 상품권 구매                                          │
├────────────────────────────────────────────────────────────────────┤
│ 의미: "회원의 현금이 줄고, 상품권 잔액이 생겼다"                     │
│                                                                    │
│ 차변(DEBIT):  VOUCHER_BALANCE  50,000원  (상품권 잔액 증가 ↑)      │
│ 대변(CREDIT): MEMBER_CASH      50,000원  (회원 현금 감소 ↓)        │
│                                                                    │
│ 비유: "현금 주머니에서 50,000원을 꺼내서 상품권 주머니에 넣었다"     │
└────────────────────────────────────────────────────────────────────┘

┌────────────────────────────────────────────────────────────────────┐
│ 거래: 카페에서 30,000원 결제                                        │
├────────────────────────────────────────────────────────────────────┤
│ 의미: "상품권 잔액이 줄고, 가맹점이 받을 돈이 생겼다"               │
│                                                                    │
│ 차변(DEBIT):  MERCHANT_RECEIVABLE  30,000원  (가맹점 미수금 ↑)     │
│ 대변(CREDIT): VOUCHER_BALANCE      30,000원  (상품권 잔액 ↓)       │
│                                                                    │
│ 비유: "상품권 주머니에서 30,000원을 꺼내서 카페 금고에 넣었다"      │
│       (아직 카페에 현금은 안 줌. 나중에 정산 때 줄 예정)            │
└────────────────────────────────────────────────────────────────────┘
```

---

## 2. 원장 (Ledger)

### 일상생활 비유

**통장:**
- 내 계좌의 입출금 내역서
- 시간순으로 모든 거래가 기록됨
- 한번 찍히면 수정/삭제 불가 (은행에서 직접 취소 처리해야 함)

**원장은 "시스템 전체의 통장"입니다.**

### 정의

원장(Ledger)은 모든 금융 거래를 **시간순으로 영구 기록**한 장부입니다.

핵심 특징:
1. **불변(Immutable)**: 한번 쓰면 절대 수정/삭제 안 됨
2. **추가 전용(Append-Only)**: 수정이 필요하면 "반대 기록"을 추가
3. **완전(Complete)**: 모든 자금 이동이 빠짐없이 기록됨

### 이 프로젝트의 코드

```kotlin
// LedgerEntry.kt — 원장의 한 줄(하나의 기록)
@Entity
@Immutable  // ⭐ Hibernate에게 "절대 UPDATE/DELETE 하지 마!" 선언
@Table(name = "ledger_entries")
class LedgerEntry(
    // 어떤 계정에 기록하는가? (예: 상품권잔액, 가맹점미수금)
    @Enumerated(EnumType.STRING)
    val account: AccountCode,
    
    // 차변인가 대변인가?
    @Enumerated(EnumType.STRING)
    val side: LedgerEntrySide,  // DEBIT 또는 CREDIT
    
    // 금액 (항상 양수!)
    val amount: BigDecimal,
    
    // 어떤 거래에 의한 기록인가?
    val transactionId: Long,
    
    // 어떤 유형의 거래인가? (구매/결제/취소/환불/만료)
    @Enumerated(EnumType.STRING)
    val entryType: LedgerEntryType,
    
    // 언제 기록되었는가?
    val createdAt: LocalDateTime = LocalDateTime.now(),
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0L

    init {
        require(amount > BigDecimal.ZERO) { "Amount must be positive" }
        // 금액이 0이거나 음수인 원장 기록은 존재할 수 없음!
    }
}
```

### @Immutable이 하는 일

```kotlin
// @Immutable이 없으면:
ledgerEntry.amount = BigDecimal("99999")  // JPA가 UPDATE 실행! ❌ (조작 가능!)

// @Immutable이 있으면:
ledgerEntry.amount = BigDecimal("99999")  // JPA가 무시! UPDATE 쿼리 안 나감! ✅
// Hibernate: "이 엔티티는 읽기 전용이야. 변경 감지(dirty checking) 안 해."
```

### 실제 DB에 저장되는 모습

```
ledger_entries 테이블:

id | account              | side   | amount | transactionId | entryType   | createdAt
---|----------------------|--------|--------|---------------|-------------|-------------------
1  | VOUCHER_BALANCE      | DEBIT  | 50000  | 1             | PURCHASE    | 2024-01-15 10:00:00
2  | MEMBER_CASH          | CREDIT | 50000  | 1             | PURCHASE    | 2024-01-15 10:00:00
3  | MERCHANT_RECEIVABLE  | DEBIT  | 30000  | 2             | REDEMPTION  | 2024-01-15 14:30:22
4  | VOUCHER_BALANCE      | CREDIT | 30000  | 2             | REDEMPTION  | 2024-01-15 14:30:22
5  | VOUCHER_BALANCE      | DEBIT  | 30000  | 3             | CANCELLATION| 2024-01-15 15:00:00
6  | MERCHANT_RECEIVABLE  | CREDIT | 30000  | 3             | CANCELLATION| 2024-01-15 15:00:00

검증:
  DEBIT 합계:  50000 + 30000 + 30000 = 110,000
  CREDIT 합계: 50000 + 30000 + 30000 = 110,000
  ✅ 균형!
```

---

## 3. 계정과목 (Chart of Accounts)

### 일상생활 비유

가계부를 쓸 때 "식비", "교통비", "저축" 같이 카테고리를 나누죠?
계정과목은 회사의 돈을 분류하는 **카테고리 체계**입니다.

### 이 프로젝트의 계정과목 7개

```kotlin
enum class AccountCode {
    MEMBER_CASH,            // 회원 현금
    VOUCHER_BALANCE,        // 상품권 잔액
    MERCHANT_RECEIVABLE,    // 가맹점 미수금
    EXPIRED_VOUCHER,        // 만료 상품권
    REFUND_PAYABLE,         // 환불 미지급금
    SETTLEMENT_PAYABLE,     // 정산 미지급금
    REVENUE_DISCOUNT,       // 할인 수익
}
```

### 각 계정과목의 상세 설명

#### MEMBER_CASH (회원 현금)

```
의미: 회원이 상품권을 구매하기 위해 지불한 실제 돈
비유: 회원의 지갑

발생: 상품권 구매 시 (대변: 회원 돈이 나감)
소멸: 환불/철회 시 (차변: 회원에게 돈이 돌아옴)

이 시스템에서의 역할:
  - "회원이 얼마를 투입했는가"를 추적
  - 실제 은행 계좌 이체와 연동되는 계정
```

#### VOUCHER_BALANCE (상품권 잔액)

```
의미: 현재 상품권에 남아있는 사용 가능 금액
비유: 충전된 교통카드의 잔액

발생: 상품권 구매 시 (차변: 잔액 생김)
감소: 결제/환불/만료 시 (대변: 잔액 줄어듦)
복원: 거래 취소 시 (차변: 잔액 돌아옴)

핵심 불변식:
  - VOUCHER_BALANCE의 순잔액 = 상품권의 balance 필드와 일치해야 함!
  - 일치하지 않으면 → 시스템 오류 (LedgerVerificationService가 감지)
```

#### MERCHANT_RECEIVABLE (가맹점 미수금)

```
의미: 가맹점이 결제를 수락했지만, 아직 현금을 받지 못한 금액
비유: "외상값" — 물건은 팔았는데 돈은 아직 못 받음

발생: 결제 시 (차변: 받을 돈 생김)
감소: 거래 취소 시 (대변: 받을 돈 소멸)
해소: 정산 확정 시 (대변: 미수금 → 정산금으로 전환)

실무에서:
  - 카드사가 가맹점에 주는 정산금과 동일한 개념
  - 결제일과 정산일 사이의 시간 차이로 발생
```

#### EXPIRED_VOUCHER (만료 상품권)

```
의미: 유효기간이 지나서 더 이상 사용할 수 없는 상품권의 잔액
비유: 유통기한 지난 식품 — 가치가 사라짐

발생: 만료 처리 시 (차변: 만료 금액 인식)
특징: 발행기관(지자체)의 수입이 됨

실무에서:
  - 만료된 잔액은 지자체 예산으로 환수
  - 만료율이 너무 높으면 소비자 불만 → 유효기간 연장 정책
```

#### REFUND_PAYABLE (환불 미지급금)

```
의미: 환불/철회가 확정되어 회원에게 돈을 돌려줘야 하는 의무
비유: "환불 대기 중" — 환불 처리는 됐는데 아직 계좌이체 전

발생: 환불/철회 시 (차변: 돌려줘야 할 돈 발생)
해소: 실제 계좌이체 완료 시 (대변: 의무 해소)

이 시스템에서:
  - 잔액환불(REFUND)과 청약철회(WITHDRAWAL) 모두 여기로 기록
  - 실제 입금 처리는 외부 시스템(은행 API)에서 처리
```

#### SETTLEMENT_PAYABLE (정산 미지급금)

```
의미: 정산이 확정되어 가맹점에 돈을 줘야 하는 의무
비유: "월급날이 왔다" — 정산 확정되었으니 가맹점에 입금해야 함

발생: 정산 확정(CONFIRMED) 시 (차변: 지급 의무 발생)
해소: 실제 계좌이체 완료 시 (대변: 의무 해소)

실무에서:
  - 정산 주기: DAILY(D+1), WEEKLY(매주 월요일), MONTHLY(매월 1일)
  - 정산 확정 → 은행 API로 대량 이체 → PAID 상태
```

### 자금 흐름 전체 그림

```
                    [상품권 구매]
                         │
    ┌────────────────────┼────────────────────┐
    │                    │                    │
    ▼                    ▼                    │
MEMBER_CASH ─────→ VOUCHER_BALANCE           │
(회원 돈 나감)      (상품권 잔액 생김)         │
                         │                    │
              ┌──────────┼──────────┐         │
              │          │          │         │
              ▼          ▼          ▼         │
         [결제]      [환불/철회]  [만료]       │
              │          │          │         │
              ▼          ▼          ▼         │
    MERCHANT_    REFUND_    EXPIRED_           │
    RECEIVABLE   PAYABLE    VOUCHER           │
    (가맹점      (환불       (만료            │
     미수금)      의무)       손실)            │
              │          │                    │
              │          ▼                    │
              │     [실제 이체]               │
              │          │                    │
              │          ▼                    │
              │     MEMBER_CASH              │
              │     (회원에게 돌아감)          │
              │                              │
              ▼                              │
         [정산 확정]                          │
              │                              │
              ▼                              │
    SETTLEMENT_PAYABLE                       │
    (정산 의무)                               │
              │                              │
              ▼                              │
         [실제 이체]                          │
              │                              │
              ▼                              │
    가맹점 은행계좌                           │
    (실제 돈 수령)                            │
```

---

## 4. 차변과 대변 (Debit & Credit)

### 초보자를 위한 완전한 설명

**가장 혼란스러운 개념이지만, 규칙만 외우면 간단합니다.**

```
회계에서 차변/대변은 "왼쪽/오른쪽"이라는 의미입니다.
(직관과 반대되는 경우가 있어서 처음에 헷갈립니다)
```

### 차변/대변 규칙표

```
┌──────────────────────────────────────────────────────────┐
│                    차변 (DEBIT)  |  대변 (CREDIT)         │
├──────────────────────────────────────────────────────────┤
│ 자산(Asset)         증가 ↑     |  감소 ↓                 │
│ 비용(Expense)       증가 ↑     |  감소 ↓                 │
│ 부채(Liability)     감소 ↓     |  증가 ↑                 │
│ 수익(Revenue)       감소 ↓     |  증가 ↑                 │
└──────────────────────────────────────────────────────────┘

쉽게 외우기:
  자산/비용 → 차변이 증가 (왼쪽이 플러스)
  부채/수익 → 대변이 증가 (오른쪽이 플러스)
```

### 이 프로젝트의 계정 분류

| 계정 | 분류 | 차변(DEBIT) = | 대변(CREDIT) = |
|------|------|--------------|----------------|
| VOUCHER_BALANCE | 자산 | 잔액 증가 | 잔액 감소 |
| MERCHANT_RECEIVABLE | 자산 | 미수금 증가 | 미수금 감소 |
| MEMBER_CASH | 자산 | 돈 받음 | 돈 나감 |
| REFUND_PAYABLE | 비용(지출) | 환불 지출 인식 | - |
| SETTLEMENT_PAYABLE | 비용(지출) | 정산 지출 인식 | - |
| EXPIRED_VOUCHER | 비용(손실) | 만료 손실 인식 | - |

### 거래별 분개(Journal Entry) 완전 해설

#### 상품권 구매 (50,000원)

```
의미: "회원이 현금 50,000원을 내고 상품권을 받았다"

분개:
  차변: VOUCHER_BALANCE  50,000  → 자산 증가 (상품권 잔액이 생김)
  대변: MEMBER_CASH      50,000  → 자산 감소 (회원 현금이 줄음)

T 계정 (장부에 그리는 방식):
  ┌─── VOUCHER_BALANCE ──┐     ┌─── MEMBER_CASH ─────┐
  │ (차변)   │ (대변)    │     │ (차변)   │ (대변)   │
  │ 50,000   │           │     │          │ 50,000   │
  └──────────┴───────────┘     └──────────┴──────────┘
    자산 ↑                        자산 ↓
```

#### 상품권 결제 (카페에서 30,000원)

```
의미: "상품권 잔액에서 30,000원이 나가고, 카페가 받을 돈 30,000원이 생겼다"

분개:
  차변: MERCHANT_RECEIVABLE  30,000  → 자산 증가 (카페의 미수금 생김)
  대변: VOUCHER_BALANCE      30,000  → 자산 감소 (상품권 잔액 줄음)

코드:
  ledgerService.record(
      debitAccount = AccountCode.MERCHANT_RECEIVABLE,
      creditAccount = AccountCode.VOUCHER_BALANCE,
      amount = amount,
      transactionId = tx.id,
      entryType = LedgerEntryType.REDEMPTION,
  )
```

#### 거래 취소 (위 결제를 취소)

```
의미: "카페의 미수금이 사라지고, 상품권 잔액이 돌아온다"

분개 (원래 결제의 정확히 반대!):
  차변: VOUCHER_BALANCE      30,000  → 자산 증가 (잔액 복원)
  대변: MERCHANT_RECEIVABLE  30,000  → 자산 감소 (미수금 소멸)

결과: 원래 결제와 취소의 순효과 = 0 (상쇄됨!)
  VOUCHER_BALANCE: +50,000(구매) -30,000(결제) +30,000(취소) = 50,000
  MERCHANT_RECEIVABLE: +30,000(결제) -30,000(취소) = 0
```

#### 잔액환불 (남은 20,000원 환불)

```
의미: "상품권 잔액이 사라지고, 회원에게 돌려줘야 할 의무가 생겼다"

분개 (실제 코드):
  차변: REFUND_PAYABLE   20,000
  대변: VOUCHER_BALANCE  20,000

해석:
  이 프로젝트에서 REFUND_PAYABLE의 차변 기록은 
  "환불금 지급 처리(지출)"를 의미합니다.
  
  전통 회계에서는 부채(Payable) 계정에 차변 = 부채 감소이지만,
  이 시스템에서는 REFUND_PAYABLE을 "환불 지출 계정"으로 사용합니다.
  즉, "환불에 20,000원을 지출했다"라는 비용 인식의 의미입니다.
  
  실제 지급(계좌이체)은 외부 시스템에서 별도 처리되며,
  이 분개는 "환불 의사결정이 확정됨"을 기록하는 것입니다.

코드:
  ledgerService.record(
      debitAccount = AccountCode.REFUND_PAYABLE,
      creditAccount = AccountCode.VOUCHER_BALANCE,
      amount = refundAmount,
      transactionId = tx.id,
      entryType = LedgerEntryType.REFUND,
  )
```

#### 만료 처리 (유효기간 지남)

```
의미: "상품권 잔액이 사라지고, 만료 손실로 처리된다"

분개:
  차변: EXPIRED_VOUCHER  20,000  → 만료 비용 인식
  대변: VOUCHER_BALANCE  20,000  → 상품권 잔액 소멸

의미: "돈이 사라진 게 아니라, '만료 처리됨'이라는 곳으로 이동한 것"
→ 지자체 입장에서는 이 금액이 '수입'이 됨 (사장 잔액)
```

---

## 5. 보상 트랜잭션 (Compensating Transaction)

### 일상생활 비유

```
학교 출석부:
  ❌ 잘못된 방법: 지우개로 "출석"을 지우고 "결석"으로 수정
  ✅ 올바른 방법: "출석" 옆에 줄을 긋고 "정정: 결석" 추가 + 정정 사유 작성

은행 수표:
  ❌ 잘못된 방법: 수표를 찢어버림 (증거 인멸)
  ✅ 올바른 방법: 수표에 "VOID" 도장 + 취소 전표 발행 (둘 다 보관)

금융 시스템:
  ❌ DELETE FROM transactions WHERE id = 7  (기록 삭제!)
  ✅ 새로운 취소 거래 생성 + 원본은 그대로 보존
```

### 정의

보상 트랜잭션(Compensating Transaction)이란:
> 이미 완료된 거래를 "논리적으로" 되돌리기 위해, 
> **반대 방향의 새로운 거래를 생성**하는 패턴.
> 원본 거래는 절대 삭제/수정하지 않습니다.

### 왜 삭제/수정하면 안 되나?

```
1. 법적 문제: 금융 기록 삭제 = 증거 인멸 가능성
2. 감사 불가: "왜 취소했는지" 추적 불가
3. 외래키 깨짐: 원장이 거래 ID를 참조하는데 거래를 삭제하면?
4. 시간 왜곡: "1월에 100만원 결제됐다"는 사실은 변하지 않음
   (2월에 취소됐다면 "2월에 취소됨"이 기록돼야지, 1월 기록을 지우면 안 됨)
```

### 이 프로젝트의 전체 흐름

```
[원래 결제]
─────────────────────────────────────────────
Transaction #7:
  type: REDEMPTION
  amount: 30,000
  status: COMPLETED  ← 나중에 CANCELLED로 변경 (내용은 불변!)
  voucherId: 1
  merchantId: 5

LedgerEntry #13:
  account: MERCHANT_RECEIVABLE, side: DEBIT, amount: 30,000, txId: 7

LedgerEntry #14:
  account: VOUCHER_BALANCE, side: CREDIT, amount: 30,000, txId: 7

Voucher #1:
  balance: 20,000 (50,000 - 30,000)
  status: PARTIALLY_USED


[취소 실행 후]
─────────────────────────────────────────────
Transaction #7: (원본 - 상태만 변경, 나머지 불변!)
  type: REDEMPTION
  amount: 30,000     ← 그대로!
  status: CANCELLED  ← 상태만 변경
  voucherId: 1
  merchantId: 5

Transaction #8: (새로 생성된 보상 거래!)
  type: CANCELLATION
  amount: 30,000
  status: COMPLETED
  voucherId: 1
  merchantId: 5
  originalTransactionId: 7  ← ⭐ 원본과 연결!

LedgerEntry #13: (그대로! 절대 수정 안 함!)
  account: MERCHANT_RECEIVABLE, side: DEBIT, amount: 30,000, txId: 7

LedgerEntry #14: (그대로!)
  account: VOUCHER_BALANCE, side: CREDIT, amount: 30,000, txId: 7

LedgerEntry #15: (새로 추가! 역방향!)
  account: VOUCHER_BALANCE, side: DEBIT, amount: 30,000, txId: 8

LedgerEntry #16: (새로 추가! 역방향!)
  account: MERCHANT_RECEIVABLE, side: CREDIT, amount: 30,000, txId: 8

Voucher #1:
  balance: 50,000 (20,000 + 30,000 복원!)
  status: ACTIVE (잔액이 전액 복원되었으므로)


[검증]
─────────────────────────────────────────────
MERCHANT_RECEIVABLE 순잔액:
  DEBIT: #13 = 30,000
  CREDIT: #16 = 30,000
  순잔액 = 30,000 - 30,000 = 0 ✅ (상쇄됨!)

VOUCHER_BALANCE 순잔액:
  DEBIT: #1(구매) 50,000 + #15(취소복원) 30,000 = 80,000
  CREDIT: #14(결제) 30,000 = 30,000
  순잔액 = 80,000 - 30,000 = 50,000 ✅ (voucher.balance와 일치!)

전체 균형:
  DEBIT 합계: 50,000 + 30,000 + 30,000 = 110,000
  CREDIT 합계: 50,000 + 30,000 + 30,000 = 110,000 ✅
```

---

## 6. 정산 (Settlement)

### 일상생활 비유

```
[카드 결제의 정산 과정]

1월 15일: 카페에서 5,000원 카드 결제
  → 카드사가 "카페에 5,000원 줘야 함" 기록 (미수금 발생)
  → 카페에 바로 돈을 주지 않음!

1월 16일: 같은 카페에서 3,000원 카드 결제
  → "카페에 줘야 할 돈" = 5,000 + 3,000 = 8,000원

1월 17일: 어제 결제 3,000원 취소
  → "카페에 줘야 할 돈" = 8,000 - 3,000 = 5,000원

1월 31일: [정산일]
  카드사 → 카페 계좌에 5,000원 입금!
  → "정산 완료"

이것이 "정산"입니다. 거래는 실시간이지만, 돈은 나중에 모아서 줍니다.
```

### 이 프로젝트의 정산

```kotlin
// Settlement.kt
class Settlement(
    val merchantId: Long,        // 어느 가맹점의 정산인가
    val periodStart: LocalDate,  // 정산 시작일 (예: 2024-01-01)
    val periodEnd: LocalDate,    // 정산 종료일 (예: 2024-01-31)
    var totalAmount: BigDecimal, // 정산 금액
    var status: SettlementStatus,// 현재 상태
)
```

### 정산 금액 계산

```
정산 금액 = (기간 내 결제 완료 합계) - (기간 내 취소 완료 합계)

예:
  1월 1일~31일 동안:
  - 결제 완료: 10,000 + 10,000 + 10,000 = 30,000원
  - 취소 완료: 10,000원 (1건 취소)
  
  정산 금액 = 30,000 - 10,000 = 20,000원
```

### 정산 상태 머신

```
         ┌─── 이의제기 ──→ DISPUTED ─── 해결 ──┐
         │                                      │
         │                                      ▼
PENDING ─┴───── 확정 ──────→ CONFIRMED ─── 지급 ──→ PAID

PENDING:   "계산됨, 아직 확정 전"
DISPUTED:  "가맹점이 이의 제기함" (금액 불일치 주장)
CONFIRMED: "확정됨, 지급 대기 중"
PAID:      "실제 계좌이체 완료"
```

### 중복 정산 방지

```kotlin
@Table(uniqueConstraints = [
    UniqueConstraint(
        name = "uk_settlement_period",
        columnNames = ["merchantId", "periodStart", "periodEnd"]
    )
])

// 의미: 같은 가맹점의 같은 기간에 정산이 2번 생성되면 DB 에러!
// 예: 카페A의 2024-01-01~2024-01-31 정산은 딱 1번만 존재 가능
```

**테스트 (BoundaryTest.kt):**
```kotlin
@Test
fun `should reject duplicate settlement for same period`() {
    // 1차 정산: 성공
    settlementService.calculate(merchantId, start, end)
    
    // 2차 정산: 거절! (같은 기간 중복)
    shouldThrow<BusinessException> {
        settlementService.calculate(merchantId, start, end)
    }.errorCode shouldBe ErrorCode.INVALID_INPUT
}
```

---

## 7. 청약철회 (Cooling-off Period)

### 일상생활 비유

```
인터넷 쇼핑:
  - 물건 받은 후 7일 이내
  - 택을 안 뗐으면 (사용 안 했으면)
  - 무조건 반품 가능! (이유 안 물어봄)
  - 이것이 "청약철회권" (소비자의 무조건적 권리)

상품권:
  - 구매 후 7일 이내
  - 한 번도 사용 안 했으면 (ACTIVE 상태)
  - 무조건 전액 환불! (이유 안 물어봄)
```

### 법적 근거

**전자상거래 등에서의 소비자보호에 관한 법률 제17조:**
> "소비자는 재화 등의 공급을 받은 날부터 7일 이내에 
>  청약의 철회를 할 수 있다."

### 이 프로젝트의 코드

```kotlin
// Voucher.kt
fun requestWithdrawal(now: LocalDateTime = LocalDateTime.now()) {
    // 조건 1: 미사용 상태(ACTIVE)만 가능
    if (status != VoucherStatus.ACTIVE)
        throw BusinessException(ErrorCode.WITHDRAWAL_NOT_ALLOWED)
    // → "한 번이라도 사용했으면 철회 불가"
    
    // 조건 2: 구매 후 7일 이내만 가능
    if (purchasedAt.plusDays(7).isBefore(now))
        throw BusinessException(ErrorCode.WITHDRAWAL_PERIOD_EXPIRED)
    // → "8일째부터는 철회 불가"
    
    status = VoucherStatus.WITHDRAWAL_REQUESTED
}

fun completeWithdrawal(): BigDecimal {
    if (status != VoucherStatus.WITHDRAWAL_REQUESTED)
        throw BusinessException(ErrorCode.INVALID_STATE_TRANSITION)
    
    val refundAmount = balance  // 전액! (= faceValue와 동일)
    balance = BigDecimal.ZERO
    status = VoucherStatus.WITHDRAWN
    return refundAmount
}
```

### 경계값 테스트

```kotlin
// BoundaryTest.kt
@Test
fun `should allow withdrawal on day 7`() {
    // 구매 후 6일 23시간 지남 (아직 7일 이내!)
    val voucher = fixtures.issueVoucher(...)
    fixtures.forcePurchasedAt(voucher.id, LocalDateTime.now().minusDays(6).minusHours(23))
    
    val withdrawn = withdrawalService.withdraw(voucher.id, memberId)
    withdrawn.status shouldBe VoucherStatus.WITHDRAWN  // ✅ 성공!
}

@Test
fun `should reject withdrawal on day 8`() {
    // 구매 후 8일 지남 (7일 초과!)
    val voucher = fixtures.issueVoucher(...)
    fixtures.forcePurchasedAt(voucher.id, LocalDateTime.now().minusDays(8))
    
    shouldThrow<BusinessException> {
        withdrawalService.withdraw(voucher.id, memberId)
    }.errorCode shouldBe ErrorCode.WITHDRAWAL_PERIOD_EXPIRED  // ❌ 거절!
}
```

---

## 8. 잔액환불 (Partial Refund)

### 일상생활 비유

```
10만원 문화상품권을 7만원 사용했다.
남은 3만원... 쓸 데가 없는데 현금으로 돌려받을 수 있을까?

규정:
  - 60% 이상 사용했으면 → 나머지를 현금으로 환불 가능!
  - 60% 미만이면 → 더 쓰세요 (환불 불가)

이유:
  - 소액 잔액이 "사장(死藏)"되는 것을 방지 (소비자 보호)
  - 60% 기준: "충분히 사용한 후"에만 환불 허용 (남용 방지)
```

### 이 프로젝트의 규칙

```
사용률(usageRatio) = (액면가 - 잔액) / 액면가

예:
  액면가 = 50,000원, 잔액 = 20,000원
  사용률 = (50,000 - 20,000) / 50,000 = 30,000 / 50,000 = 0.60 (60%)

환불 가능 조건:
  1. 상태가 PARTIALLY_USED (한 번 이상 사용함)
  2. 사용률 >= 60% (지자체 정책: refundThresholdRatio)
  3. 환불 금액 = 현재 잔액 (남은 돈 전부)
```

### 코드

```kotlin
// Voucher.kt
val usageRatio: BigDecimal
    get() = (faceValue - balance).divide(faceValue, 4, RoundingMode.HALF_UP)
    // 소수점 4자리, 반올림

fun requestRefund(refundThresholdRatio: BigDecimal) {
    // 조건 1: PARTIALLY_USED만 (ACTIVE = 미사용이니 해당 안 됨)
    if (status != VoucherStatus.PARTIALLY_USED)
        throw BusinessException(ErrorCode.REFUND_CONDITION_NOT_MET, 
            "부분 사용된 상품권만 환불 가능합니다")
    
    // 조건 2: 60% 이상 사용
    if (usageRatio < refundThresholdRatio)
        throw BusinessException(ErrorCode.REFUND_CONDITION_NOT_MET)
    
    status = VoucherStatus.REFUND_REQUESTED
}
```

### 청약철회 vs 잔액환불 최종 비교

```
┌──────────────────────────────────────────────────────────────────┐
│                    청약철회                잔액환불               │
├──────────────────────────────────────────────────────────────────┤
│ 대상 상태      │ ACTIVE (미사용)    │ PARTIALLY_USED (사용 중)  │
│ 기간 조건      │ 7일 이내          │ 없음                      │
│ 사용량 조건    │ 없음 (미사용만)    │ 60% 이상 사용            │
│ 환불 금액      │ 전액 (faceValue)   │ 잔액만 (balance)          │
│ 법적 근거      │ 전자상거래법       │ 지역화폐 조례             │
│ 소비자 권리    │ 무조건적           │ 조건부                    │
│ 결과 상태      │ WITHDRAWN          │ REFUNDED                 │
│ 원장 기록      │ DEBIT REFUND_PAYABLE, CREDIT VOUCHER_BALANCE   │
│                │ (동일한 원장 구조)                              │
└──────────────────────────────────────────────────────────────────┘
```

---

## 9. 멱등성 (Idempotency)

### 일상생활 비유

```
엘리베이터 버튼:
  1번 누르기: 엘리베이터가 옴
  3번 누르기: 엘리베이터가 옴 (1번 누른 것과 같은 결과)
  → 멱등!

자판기:
  버튼 1번 누르기: 음료 1개 나옴
  버튼 3번 누르기: 음료 3개 나옴 (돈도 3번 빠져나감!)
  → 멱등하지 않음!

결제 시스템에서:
  "결제 요청을 3번 보내면 3번 결제될까?"
  → 멱등하지 않으면: YES! 3만원 결제가 3번 = 9만원 빠져나감! 💸
  → 멱등하면: NO! 첫 번째 결과만 유효, 나머지는 무시됨 ✅
```

### 왜 금융에서 특히 중요한가?

```
네트워크는 불안정합니다:

Client → "결제 30,000원" → Server: 처리 완료! → 응답 → X (타임아웃!)

Client: "응답을 못 받았으니 실패인가 보다. 다시 보내자."
Client → "결제 30,000원" → Server: ???

멱등키 없으면:
  Server: "새로운 요청이네? 또 30,000원 차감!" → 이중 결제! 💸💸

멱등키 있으면:
  Server: "이 키(abc-123)로 이미 처리했네. 이전 결과 반환할게."
  → 안전! 중복 처리 방지! ✅
```

### 이 프로젝트에서의 동작

```
[첫 번째 요청]
─────────────────────────────────────────────
POST /api/v1/vouchers/1/redeem
Headers: X-Idempotency-Key: "abc-123-def"
Body: { "merchantId": 5, "amount": 30000 }

서버 처리:
1. Redis 검색: "idempotency:abc-123-def" → 없음
2. DB 검색: → 없음
3. 실제 결제 실행! → 성공 (txId=42, balance=20000)
4. Redis에 저장: key="idempotency:abc-123-def", 
                 value={status:200, body:{txId:42, balance:20000}},
                 TTL=24h
5. 응답: 200 OK { txId: 42, balance: 20000 }


[같은 키로 재요청 (네트워크 타임아웃 후 재시도)]
─────────────────────────────────────────────
POST /api/v1/vouchers/1/redeem
Headers: X-Idempotency-Key: "abc-123-def"  ← 같은 키!
Body: { "merchantId": 5, "amount": 30000 }

서버 처리:
1. Redis 검색: "idempotency:abc-123-def" → 있음! ✅
2. 비즈니스 로직 실행하지 않음!!! (결제 안 함!)
3. 저장된 이전 응답을 그대로 반환
4. 응답: 200 OK { txId: 42, balance: 20000 }  ← 이전과 동일한 응답!
```

---

## 10. 가맹점 미수금 (Merchant Receivable)

### 일상생활 비유

```
당신이 카페를 운영합니다.

1월 15일: 손님이 상품권으로 5,000원 결제
  → 돈은 아직 안 들어왔지만, "5,000원 받을 권리"가 생김
  → 이것이 "미수금" (Receivable)

1월 16일: 또 다른 손님, 3,000원 상품권 결제
  → 미수금 = 5,000 + 3,000 = 8,000원

1월 31일: 정산 (실제 입금!)
  → 카페 계좌에 8,000원 입금됨
  → 미수금 0원으로 해소

정리: 미수금 = "받을 돈은 있지만 아직 못 받은 상태"
```

### 이 프로젝트의 자금 흐름

```
[결제 발생]
  MERCHANT_RECEIVABLE ↑ (가맹점의 미수금 증가)
  VOUCHER_BALANCE ↓ (상품권 잔액 감소)

[거래 취소]
  MERCHANT_RECEIVABLE ↓ (가맹점의 미수금 감소)
  VOUCHER_BALANCE ↑ (상품권 잔액 복원)

[정산 확정]
  MERCHANT_RECEIVABLE → SETTLEMENT_PAYABLE
  (미수금이 해소되고, 정산 지급 의무가 생김)

[실제 입금]
  SETTLEMENT_PAYABLE → 가맹점 은행계좌
  (지급 의무 해소)
```

### 정산 금액 계산 예시

```
카페A의 1월 거래 내역:
  1/5:  결제 10,000원 ✅
  1/10: 결제 10,000원 ✅
  1/15: 결제 10,000원 ✅
  1/15: 결제 취소 10,000원 (1/5 결제를 취소) ❌

정산 금액 = 결제 합계 - 취소 합계
          = 30,000 - 10,000 = 20,000원

원장 확인:
  MERCHANT_RECEIVABLE(카페A) 순잔액:
  DEBIT: 10,000 + 10,000 + 10,000 = 30,000  (3건 결제)
  CREDIT: 10,000                    = 10,000  (1건 취소)
  순잔액 = 30,000 - 10,000 = 20,000원 ✅ (정산 금액과 일치!)
```

---

## 11. 만료 처리 (Expiration)

### 일상생활 비유

```
식품의 유통기한:
  유통기한 내: 먹을 수 있음 (사용 가능)
  유통기한 경과: 먹을 수 없음 (폐기)

상품권의 유효기간:
  유효기간 내: 결제에 사용 가능
  유효기간 경과: 사용 불가 (만료)

만료된 잔액은 어떻게 되나?
  → 발행 기관(지자체)으로 귀속 (사장 잔액)
  → 원장에 "만료 처리됨"으로 기록
```

### 이 프로젝트의 만료 처리

```kotlin
// Voucher.kt — 도메인 엔티티의 만료 메서드
fun isExpired(now: LocalDateTime = LocalDateTime.now()): Boolean = 
    expiresAt.isBefore(now)
// expiresAt = purchasedAt + 6개월

fun expire() {
    if (!isUsable()) throw BusinessException(...)
    status = VoucherStatus.EXPIRED
    // 주의: 여기서는 상태만 변경! balance는 스케줄러에서 별도 처리
}
```

```kotlin
// VoucherExpiryScheduler.kt — 실제 만료 배치 처리
// 스케줄러에서의 처리:
val remainingBalance = locked.balance  // 남은 잔액 캡처
locked.expire()                        // 상태 → EXPIRED
locked.balance = BigDecimal.ZERO       // ⭐ 잔액을 0으로 명시 설정!

// 잔액이 있었다면 원장에 기록
if (remainingBalance > BigDecimal.ZERO) {
    ledgerService.record(
        debitAccount = AccountCode.EXPIRED_VOUCHER,
        creditAccount = AccountCode.VOUCHER_BALANCE,
        amount = remainingBalance,
        ...
    )
}
```

### 배치 스케줄러 (5분마다 실행)

```
매 5분마다:
1. 만료 대상 조회:
   WHERE status IN ('ACTIVE', 'PARTIALLY_USED') 
   AND expiresAt <= NOW()

2. 각 상품권별로 독립 처리 (REQUIRES_NEW 트랜잭션):
   - 분산락 획득 (voucher:{id})
   - 이중 체크 (락 대기 중 이미 만료됐는지 확인)
   - 남은 잔액 캡처 후 balance = 0으로 설정
   - 잔액 > 0이면 원장 기록:
     DEBIT EXPIRED_VOUCHER, CREDIT VOUCHER_BALANCE
   - 만료 거래(Transaction) 생성
   - 상태를 EXPIRED로 변경
   - 분산락 해제

3. 각 건이 독립 트랜잭션이므로, 한 건 실패가 다른 건에 영향 없음
4. 실패한 건은 다음 5분 주기에 자동 재시도
```

### 왜 5분 간격인가?

```
너무 짧으면 (1초): DB 부하 과다
너무 길면 (1시간): 만료된 상품권으로 1시간 동안 결제 가능! 문제!

5분: 합리적 지연 시간
  - 최악: 만료 후 5분 동안 사용 가능할 수도 있음
  - 방어: redeem() 메서드에서 isExpired() 체크로 추가 방어
    → 실시간으로 만료 여부를 확인하므로 실제로는 사용 불가
```

---

## 12. 감사 추적 (Audit Trail)

### 일상생활 비유

```
CCTV:
  - 24시간 녹화
  - "누가 언제 무엇을 했는지" 기록
  - 사건 발생 시 증거로 사용
  - 삭제하면 증거 인멸

감사 로그:
  - 모든 금융 행위를 기록
  - "누가 언제 얼마를 어떻게 처리했는지"
  - 금감원 감사 시 제출
  - 삭제/수정 불가
```

### 이 프로젝트의 감사 로그

```kotlin
// AuditLog.kt
class AuditLog(
    val eventId: String,          // 고유 ID (UUID)
    val eventType: String,        // "VOUCHER_ISSUED", "VOUCHER_REDEEMED" 등
    val severity: AuditSeverity,  // CRITICAL, HIGH, MEDIUM
    val aggregateType: String,    // "Voucher", "Transaction" 등
    val aggregateId: Long,        // 상품권 ID, 거래 ID 등
    val actorId: Long?,           // 행위자 (회원 ID, 관리자 ID)
    val action: String,           // "CREATE", "STATE_CHANGE", "CANCEL"
    val previousState: String?,   // 변경 전 상태 (JSON)
    val currentState: String?,    // 변경 후 상태 (JSON)
    val metadata: String?,        // 추가 정보 (JSON)
    val createdAt: LocalDateTime  // DATETIME(6) — 마이크로초 정밀도
)
```

### 심각도별 처리의 의미

```
CRITICAL (결제, 발행, 환불, 취소):
  "이 감사 로그 없이는 거래도 허용하지 않겠다!"
  → 감사 로그 저장 실패 = 거래 자체를 롤백
  → "기록 없는 거래"는 존재할 수 없음

HIGH (가맹점 승인, 회원 정지):
  "중요하지만, 이것 때문에 비즈니스를 멈추지는 않겠다"
  → 감사 로그 실패해도 비즈니스 TX는 성공
  → 실패한 이벤트는 FailedEvent에 저장 → 나중에 재시도

MEDIUM (일반 조회, 설정 변경):
  "기록은 하되, 실패해도 큰 문제 아님"
  → 최선 노력(best-effort)으로 기록
```

---

## 13. 불변식 (Invariant)

### 일상생활 비유

```
물리 법칙:
  "에너지는 생성되거나 소멸되지 않는다" (에너지 보존 법칙)
  → 어떤 상황에서도 항상 참!

교통 규칙:
  "신호등이 빨간불일 때 차는 멈춰야 한다"
  → 이 규칙이 깨지면 사고 발생!

금융 시스템의 불변식:
  "잔액은 절대 음수가 되면 안 된다"
  → 이 규칙이 깨지면 없는 돈을 사용한 것!
```

### 정의

불변식(Invariant) = **시스템이 어떤 상황에서도 항상 참이어야 하는 조건**

불변식이 깨지면 = 시스템 무결성 손상 = 심각한 버그/보안 문제

### 이 프로젝트의 6가지 불변식

#### I1: 잔액은 절대 음수가 되면 안 된다

```
voucher.balance >= 0  (항상!)

위반 시 의미: 
  "없는 돈을 사용한 것" = 무에서 유를 창조한 것 = 자금 유출!

보장 방법:
  1. 분산락으로 동시 접근 직렬화
  2. SELECT FOR UPDATE로 2차 방어
  3. voucher.redeem()에서 balance < amount 체크
  4. init 블록에서 require(balance >= 0)
```

#### I2: 원장의 차변 합계 = 대변 합계 (항상)

```
sum(DEBIT) == sum(CREDIT)  (전역)

위반 시 의미:
  "돈이 공중에서 나타나거나 사라진 것" = 시스템 오류!

보장 방법:
  1. LedgerService.record()가 항상 2행(DEBIT+CREDIT) 동시 생성
  2. 같은 DB 트랜잭션에서 원자적 처리
  3. LedgerVerificationService가 매일 02:00에 검증
```

#### I3: 유효하지 않은 상태 전이는 불가

```
예: EXHAUSTED → ACTIVE (불가!)
    "다 쓴 상품권이 갑자기 부활하면 안 됨"

보장 방법:
  엔티티 메서드에서 현재 상태 검증:
  fun requestRefund(...) {
      if (status != VoucherStatus.PARTIALLY_USED) throw ...
  }
```

#### I4: 원장 엔트리는 불변

```
한번 기록된 LedgerEntry는 수정/삭제 불가

보장 방법:
  @Immutable 어노테이션 (Hibernate가 UPDATE/DELETE 쿼리 차단)
```

#### I5: 동일 기간 중복 정산 불가

```
같은 가맹점의 같은 기간 정산이 2번 생성되면 안 됨

보장 방법:
  DB UNIQUE 제약: (merchantId, periodStart, periodEnd)
```

#### I6: 멱등키 중복 시 동일 결과

```
같은 멱등키로 요청하면 항상 같은 결과

보장 방법:
  Redis + DB에 이전 결과 저장 → 동일 키면 저장된 결과 반환
```

---

## 14. 분산 잠금 (Distributed Lock)

### 일상생활 비유

```
화장실 문 잠금:
  - 1인용 화장실에 들어감 → 문 잠금 (Lock)
  - 다른 사람이 오면 → "사용 중" 표시 → 대기
  - 볼일 끝남 → 문 열기 (Unlock)
  - 다음 사람 입장

분산 잠금:
  - 여러 서버에서 같은 상품권에 접근 → 분산 잠금 (Redis Lock)
  - 다른 서버의 요청은 대기
  - 처리 완료 → 잠금 해제
  - 다음 요청 처리
```

### 왜 "분산" 잠금인가?

```
[서버가 1대일 때]
  Java의 synchronized 키워드면 충분!
  → 같은 JVM 내에서 스레드 간 동기화

[서버가 여러 대일 때 (분산 환경)]
  서버 A: synchronized로 잠금! (서버 A 내에서만 효과)
  서버 B: synchronized로 잠금! (서버 B 내에서만 효과)
  → 서버 A와 B가 동시에 같은 상품권 접근 가능! 위험!

  해결: 외부 시스템(Redis)에서 잠금 관리
  서버 A: Redis에 "voucher:1 사용 중" 등록
  서버 B: Redis 확인 → "사용 중이네" → 대기!
```

### 이 프로젝트의 구현

```kotlin
// VoucherLockManager.kt
@Component
class VoucherLockManager(
    private val redissonClient: RedissonClient,
    private val meterRegistry: MeterRegistry,
) {
    fun <T> withVoucherLock(voucherId: Long, action: () -> T): T =
        withLock("voucher:$voucherId", action)
        // 키: "voucher:1", "voucher:2" → 각 상품권별로 독립된 잠금!
    
    fun <T> withMemberPurchaseLock(memberId: Long, action: () -> T): T =
        withLock("member:purchase:$memberId", action)
        // 키: "member:purchase:7" → 같은 회원의 동시 구매 방지

    private fun <T> withLock(key: String, action: () -> T): T {
        val lock = redissonClient.getLock(key)
        
        // tryLock(대기시간, 보유시간, 단위)
        // 5초 동안 획득 시도, 획득 후 10초간 보유
        val acquired = lock.tryLock(5, 10, TimeUnit.SECONDS)
        
        if (!acquired) {
            // 5초 대기해도 못 얻으면 → 서비스 일시 불가 응답
            throw BusinessException(ErrorCode.LOCK_ACQUISITION_FAILED)
        }
        
        try {
            return action()  // 보호된 영역에서 비즈니스 로직 실행
        } finally {
            // 반드시 잠금 해제! (예외 발생해도 해제됨)
            if (lock.isHeldByCurrentThread) lock.unlock()
        }
    }
}
```

---

## 15. 할인율과 구매한도

### 지역사랑상품권의 할인 구조

```
상품권 10,000원 구매 시 (할인율 10%):
  소비자 부담: 9,000원 (10% 할인!)
  지자체 보조금: 1,000원 (세금에서 충당)

총 재원 = 소비자 부담금 + 지자체 보조금 = 10,000원

소비자 입장: 9,000원 내고 10,000원 상품권 받음 → 이득!
지역경제 입장: 지역 내 소비 촉진 → 이득!
지자체 입장: 1,000원 투자로 10,000원 지역 소비 → 경제 활성화!
```

### 왜 구매한도가 필요한가?

```
한도가 없으면:
  부자 A씨: "할인이네? 1억원어치 사자!" → 1,000만원 이득 챙김
  → 공공재원(세금)이 특정인에게 집중!
  → 일반 시민이 사려고 할 때 물량 소진!

한도가 있으면:
  1인 월 50만원까지만 → 많은 시민이 골고루 혜택
  지역 월 10억원까지만 → 재정 건전성 유지
```

### 이 프로젝트의 정책(Policy)

```kotlin
// RegionPolicy.kt (지자체별 다른 정책 설정 가능)
data class RegionPolicy(
    val discountRate: BigDecimal,           // 할인율 (0.10 = 10%)
    val purchaseLimitPerPerson: BigDecimal, // 1인 월 구매한도 (500,000원)
    val monthlyIssuanceLimit: BigDecimal,   // 지역 월 총 발행한도 (10억원)
    val refundThresholdRatio: BigDecimal,   // 환불 기준 사용률 (0.60 = 60%)
    val settlementPeriod: SettlementPeriod  // 정산 주기 (DAILY/WEEKLY/MONTHLY)
)
```

---

## 16. 상품권 코드의 보안

### 상품권 코드 구조

```
SN-A3K9M2X7P1B4Q8R5
│   │              │
│   │              └─ 체크 디짓 (1자리): 오타 감지용
│   └─ 랜덤 코드 (15자리): 예측 불가한 난수
└─ 지역코드 (2자리): 서울=SN, 부산=BS
```

### SecureRandom (보안 난수)

```kotlin
private val random = SecureRandom()  // 암호학적으로 안전한 난수 생성기

// 일반 Random과의 차이:
// Random: 시드(seed)를 알면 다음 수를 예측 가능!
//   → 공격자가 이전 코드 패턴을 분석하면 다음 코드 추측 가능!
//   → 위험!

// SecureRandom: OS의 엔트로피(무질서도)를 사용하여 예측 불가
//   → 아무리 이전 코드를 분석해도 다음 코드 추측 불가
//   → 안전!
```

### Luhn mod 36 (체크 디짓)

```
목적: "사용자가 코드를 잘못 입력했을 때, DB 조회 전에 즉시 감지"

동작 원리:
  1. 코드 15자리로 수학적 계산 → 체크 디짓 1자리 도출
  2. 코드 입력 시 같은 계산 수행 → 계산 결과와 마지막 자리 비교
  3. 다르면 → 즉시 "잘못된 코드" (DB 조회 안 함!)

효과:
  - 한 글자 오타: 100% 감지
  - 두 글자 위치 바뀜: 대부분 감지
  - DB 부하 감소 (잘못된 코드로 인한 불필요한 조회 방지)

비유:
  주민등록번호 마지막 자리가 "검증 숫자"인 것과 같은 원리!
  → 잘못된 주민등록번호를 입력하면 마지막 자리가 안 맞아서 즉시 감지
```

---

## 17. 원장 정합성 검증

### 일상생활 비유

```
회사 결산:
  매달 말에 모든 장부를 확인
  "입금 합계 = 출금 합계" 인지 검증
  안 맞으면 → 회계사가 원인 조사!

이 시스템:
  매일 새벽 2시에 자동 검증
  "DEBIT 합계 = CREDIT 합계" 인지 확인
  안 맞으면 → CRITICAL 알림! (자동 수정은 하지 않음!)
```

### 검증 코드

```kotlin
// LedgerVerificationService.kt
@Scheduled(cron = "0 0 2 * * *")  // 매일 02:00
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
fun scheduledVerification() {
    val result = verify()
    
    if (!result.isBalanced) {
        log.error("🚨 LEDGER IMBALANCE DETECTED: {} vouchers", 
            result.imbalancedVouchers.size)
        // → 운영팀에 즉시 알림 (Slack, PagerDuty 등)
    }
}

fun verify(): VerificationResult {
    // 검증 1: 전역 차대변 균형
    val globalDebit = ledgerRepository.sumBySide(LedgerEntrySide.DEBIT)
    val globalCredit = ledgerRepository.sumBySide(LedgerEntrySide.CREDIT)
    val globalBalanced = globalDebit.compareTo(globalCredit) == 0
    
    // 검증 2: 각 상품권의 balance vs 원장 계산값
    val imbalanced = checkVoucherBalances()
    
    return VerificationResult(
        isBalanced = globalBalanced && imbalanced.isEmpty(),
        globalDebitTotal = globalDebit,
        globalCreditTotal = globalCredit,
        imbalancedVouchers = imbalanced,
    )
}
```

### 왜 자동 수정하지 않는가?

```
자동 수정의 위험:
  - 원인을 모른 채 숫자만 맞추면 → 근본 문제 은폐
  - 버그가 계속 발생해도 자동 수정이 덮어버림
  - 감사관이 "왜 이렇게 수정했나?" 물으면 답 못 함

올바른 대응:
  1. 불일치 감지 → CRITICAL 로그
  2. 운영팀 수동 조사
  3. 원인 규명 (코드 버그? 데이터 오류? 외부 조작?)
  4. 원인 해결 후 보정 분개(Adjusting Entry) 수동 생성
  5. 재발 방지 조치 (테스트 추가, 검증 로직 강화)
```

---

## 18. 지역사랑상품권 운영 구조

### 전체 생태계

```
┌─────────────────────────────────────────────────────────────────┐
│                      중앙 정부 (행정안전부)                        │
│  - 지역사랑상품권 정책 수립                                       │
│  - 예산 지원                                                     │
└────────────────────────────┬────────────────────────────────────┘
                             │ 예산 배정
                             ▼
┌─────────────────────────────────────────────────────────────────┐
│                      발행기관 (선불 바우처 발행처/지자체)                 │
│  - 상품권 발행 시스템 운영                                        │
│  - 정산 대행                                                     │
│  - 부정 사용 모니터링                                             │
│  - 이 프로젝트가 구현하는 부분!                                    │
└────────────────────────────┬────────────────────────────────────┘
                             │
            ┌────────────────┼────────────────┐
            ▼                ▼                ▼
┌───────────────┐  ┌───────────────┐  ┌───────────────┐
│   지자체 A    │  │   지자체 B    │  │   지자체 C    │
│   (서울)      │  │   (부산)      │  │   (대전)      │
│              │  │              │  │              │
│ 할인율: 10%  │  │ 할인율: 8%   │  │ 할인율: 10%  │
│ 한도: 50만원 │  │ 한도: 30만원 │  │ 한도: 50만원 │
│ 정산: 월정산 │  │ 정산: 주정산 │  │ 정산: 일정산 │
└───────┬───────┘  └───────┬───────┘  └───────┬───────┘
        │                  │                  │
        └──────────────────┼──────────────────┘
                           │
┌──────────────────────────┼──────────────────────────┐
│                    시민 (소비자)                      │
│  - 상품권 구매 (할인 적용)                           │
│  - 지역 가맹점에서 결제                              │
│  - 청약철회 (7일 이내) / 잔액환불 (60%+ 사용)       │
└──────────────────────────┬──────────────────────────┘
                           │ 상품권으로 결제
                           ▼
┌─────────────────────────────────────────────────────┐
│                    가맹점                             │
│  - 상품권 결제 수락                                  │
│  - 정산 주기에 따라 대금 수령                        │
│  - 정산 이의 제기 가능                               │
│  - 예: 동네 카페, 식당, 마트, 미용실 등              │
└─────────────────────────────────────────────────────┘
```

### 재원 구조 상세

```
상품권 10,000원 발행 시 (할인율 10%):

┌──────────────────────────────────────────┐
│         상품권 10,000원 (액면가)           │
├──────────────────┬───────────────────────┤
│  소비자 부담금    │   지자체 보조금        │
│   9,000원        │    1,000원            │
│  (90%)           │    (10%)              │
│                  │    ← 세금에서 충당     │
└──────────────────┴───────────────────────┘

이 1,000원(공공재원) 때문에:
  - 한도 관리가 필요 (세금 남용 방지)
  - 감사 추적이 필요 (공공재원 투명성)
  - 정산 정확성이 필요 (가맹점에 정확한 금액 지급)
  - 만료 처리가 필요 (미사용 재원 회수)
```

---

## 19. 금융 시스템 설계 원칙

### 원칙 1: "절대 지우지 말고, 반대로 써라"

```
일반 시스템: 잘못되면 DELETE/UPDATE
금융 시스템: 잘못되면 반대 방향 기록 추가 (원본 보존)

이유: 감사 추적 + 법적 증거 보존
```

### 원칙 2: "돈은 절대 공중에서 나타나거나 사라지면 안 된다"

```
sum(입금) == sum(출금) 항상 유지
→ 복식부기로 보장
→ 위반 시 = 시스템 오류 = 즉시 알림
```

### 원칙 3: "정확성 > 속도"

```
일반 시스템: 1ms 빨리 응답하는 것이 중요
금융 시스템: 100ms 걸려도 정확한 것이 중요

→ 분산락으로 순서 보장 (약간 느려지더라도)
→ 동기 원장 기록 (비동기보다 느리지만 정합성 보장)
```

### 원칙 4: "의심스러우면 멈춰라"

```
불일치 발견 시:
  ❌ "자동으로 수정해서 서비스 계속 제공하자"
  ✅ "멈추고 알리고 수동으로 확인하자"

이유: 잘못된 자동 수정은 더 큰 문제를 만들 수 있음
```

### 원칙 5: "모든 것을 기록하라"

```
언제, 누가, 무엇을, 왜, 얼마나 → 전부 기록!
→ AuditLog: 행위 기록
→ LedgerEntry: 자금 이동 기록
→ Transaction: 거래 이력
→ 셋 다 불변 (수정/삭제 불가)
```

---

## 20. 용어 사전

### 한국어 ↔ 영어 대응표

| 한국어 | 영어 | 이 프로젝트에서 | 쉬운 설명 |
|--------|------|----------------|-----------|
| 상품권 | Voucher | `Voucher` 엔티티 | 일정 금액을 사용할 수 있는 디지털 쿠폰 |
| 액면가 | Face Value | `faceValue` | 상품권에 적힌 금액 (변하지 않음) |
| 잔액 | Balance | `balance` | 현재 남은 사용 가능 금액 |
| 결제/사용 | Redemption | `VoucherRedemptionService` | 상품권으로 물건/서비스 구매 |
| 정산 | Settlement | `Settlement` 엔티티 | 가맹점에 대금을 모아서 지급하는 것 |
| 청약철회 | Withdrawal | `VoucherWithdrawalService` | 구매 7일 이내 무조건 반환 |
| 잔액환불 | Refund | `VoucherRefundService` | 60%+ 사용 후 잔액을 현금으로 돌려받기 |
| 미수금 | Receivable | `MERCHANT_RECEIVABLE` | 받을 돈은 있지만 아직 못 받은 금액 |
| 미지급금 | Payable | `REFUND_PAYABLE` | 줘야 할 돈은 있지만 아직 안 준 금액 |
| 분개 | Journal Entry | `LedgerService.record()` | 거래를 차변+대변으로 기록하는 행위 |
| 보상 거래 | Compensating TX | `TransactionCancelService` | 완료된 거래를 되돌리는 반대 거래 |
| 감사 | Audit | `AuditLog`, `AuditEventListener` | 기록의 정확성과 적법성 검증 |
| 복식부기 | Double-Entry | `LedgerEntry` × 2행 | 모든 거래를 차변+대변으로 기록 |
| 원장 | Ledger | `ledger_entries` 테이블 | 모든 금융 거래의 영구 기록 |
| 계정과목 | Account Code | `AccountCode` enum | 거래를 분류하는 카테고리 체계 |
| 차변 | Debit | `LedgerEntrySide.DEBIT` | 자산 증가 또는 부채 감소를 기록 |
| 대변 | Credit | `LedgerEntrySide.CREDIT` | 자산 감소 또는 부채 증가를 기록 |
| 멱등성 | Idempotency | `@Idempotent` | 같은 요청 반복 시 동일 결과 보장 |
| 불변식 | Invariant | I1~I6 | 시스템이 항상 만족해야 하는 조건 |
| 만료 | Expiration | `VoucherExpiryScheduler` | 유효기간 경과로 사용 불가 상태가 됨 |
| 사장(死藏) | Dormancy | - | 잔액이 사용되지 않고 방치되는 상태 |
| 분산 잠금 | Distributed Lock | `VoucherLockManager` | 여러 서버 간 동시 접근 제어 |
| 비관적 잠금 | Pessimistic Lock | `SELECT FOR UPDATE` | "충돌 예상" → 미리 잠그고 작업 |
| 낙관적 잠금 | Optimistic Lock | `@Version` | "충돌 안 날 것" → 나중에 확인 |
| 트랜잭션 | Transaction | DB Transaction | 전부 성공하거나 전부 실패하는 작업 단위 |
| 커밋 | Commit | `COMMIT` | 변경 사항을 확정(영구 저장)하는 것 |
| 롤백 | Rollback | `ROLLBACK` | 변경 사항을 취소(원래대로)하는 것 |
| 배치 | Batch | `@Scheduled` | 정해진 시간에 자동 실행되는 작업 |

### 약어 모음

| 약어 | 풀네임 | 의미 |
|------|--------|------|
| DDD | Domain-Driven Design | 도메인(비즈니스) 중심 설계 |
| CQRS | Command Query Responsibility Segregation | 읽기/쓰기 책임 분리 |
| MSA | Microservice Architecture | 마이크로서비스 아키텍처 |
| OSIV | Open Session In View | HTTP 요청 전체에 DB 세션 유지 |
| JWT | JSON Web Token | JSON 기반 인증 토큰 |
| TTL | Time To Live | 데이터 만료 시간 |
| CSPRNG | Cryptographically Secure PRNG | 암호학적으로 안전한 난수 생성기 |
| SRP | Single Responsibility Principle | 단일 책임 원칙 |
| YAGNI | You Ain't Gonna Need It | 지금 필요 없으면 만들지 마라 |
