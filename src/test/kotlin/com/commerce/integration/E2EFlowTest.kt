package com.commerce.integration

import com.commerce.common.audit.AuditLogRepository
import com.commerce.common.audit.AuditSeverity
import com.commerce.common.exception.BusinessException
import com.commerce.common.exception.ErrorCode
import com.commerce.ledger.application.LedgerService
import com.commerce.ledger.application.LedgerVerificationService
import com.commerce.ledger.domain.LedgerEntrySide
import com.commerce.ledger.infrastructure.LedgerJpaRepository
import com.commerce.merchant.application.SettlementService
import com.commerce.support.IntegrationTestSupport
import com.commerce.support.TestFixtures
import com.commerce.transaction.application.TransactionCancelService
import com.commerce.transaction.infrastructure.TransactionJpaRepository
import com.commerce.voucher.application.VoucherRedemptionService
import com.commerce.voucher.application.VoucherRefundService
import com.commerce.voucher.application.VoucherWithdrawalService
import com.commerce.voucher.domain.VoucherStatus
import com.commerce.voucher.infrastructure.VoucherJpaRepository
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

class E2EFlowTest : IntegrationTestSupport() {

    @Autowired lateinit var fixtures: TestFixtures
    @Autowired lateinit var redemptionService: VoucherRedemptionService
    @Autowired lateinit var refundService: VoucherRefundService
    @Autowired lateinit var withdrawalService: VoucherWithdrawalService
    @Autowired lateinit var cancelService: TransactionCancelService
    @Autowired lateinit var settlementService: SettlementService
    @Autowired lateinit var verificationService: LedgerVerificationService
    @Autowired lateinit var voucherRepository: VoucherJpaRepository
    @Autowired lateinit var ledgerRepository: LedgerJpaRepository
    @Autowired lateinit var transactionRepository: TransactionJpaRepository
    @Autowired lateinit var auditLogRepository: AuditLogRepository

    private var regionId: Long = 0
    private var memberId: Long = 0
    private var merchantId: Long = 0

    @BeforeEach
    fun setup() {
        val region = fixtures.createRegion(code = UUID.randomUUID().toString().take(2).uppercase())
        val member = fixtures.createMember()
        val merchantOwner = fixtures.createMember()
        val merchant = fixtures.createMerchant(region, merchantOwner)
        regionId = region.id
        memberId = member.id
        merchantId = merchant.id
    }

    @Test
    fun `full lifecycle - issue, partial redeem, partial redeem, refund`() {
        // 1. 발행: 50,000원
        val voucher = fixtures.issueVoucher(memberId, regionId, BigDecimal("50000"))
        voucher.status shouldBe VoucherStatus.ACTIVE
        voucher.balance.compareTo(BigDecimal("50000")) shouldBe 0

        // 2. 1차 결제: 20,000원
        val r1 = redemptionService.redeem(voucher.id, merchantId, BigDecimal("20000"))
        r1.remainingBalance.compareTo(BigDecimal("30000")) shouldBe 0

        // 3. 2차 결제: 15,000원
        val r2 = redemptionService.redeem(voucher.id, merchantId, BigDecimal("15000"))
        r2.remainingBalance.compareTo(BigDecimal("15000")) shouldBe 0

        // 상태 확인: PARTIALLY_USED, usage = 70%
        val updated = voucherRepository.findById(voucher.id).get()
        updated.status shouldBe VoucherStatus.PARTIALLY_USED
        updated.balance.compareTo(BigDecimal("15000")) shouldBe 0

        // 4. 잔액 환불: 60%+ 사용 조건 충족 (70%)
        val refunded = refundService.refund(voucher.id, memberId)
        refunded.status shouldBe VoucherStatus.REFUNDED
        refunded.balance.compareTo(BigDecimal.ZERO) shouldBe 0

        // 5. 원장 정합성 검증
        val verification = verificationService.verify()
        verification.isBalanced shouldBe true
        verification.imbalancedVouchers shouldBe emptyList()

        // 6. 원장 엔트리 수 확인: 발행(2) + 결제1(2) + 결제2(2) + 환불(2) = 8
        val allEntries = ledgerRepository.findAll()
            .filter { entry ->
                transactionRepository.findById(entry.transactionId)
                    .map { it.voucherId == voucher.id }.orElse(false)
            }
        allEntries.size shouldBe 8
    }

    @Test
    fun `transaction cancellation creates compensating entries`() {
        // 1. 발행 + 결제
        val voucher = fixtures.issueVoucher(memberId, regionId, BigDecimal("50000"))
        val result = redemptionService.redeem(voucher.id, merchantId, BigDecimal("30000"))

        // 잔액 20,000원
        voucherRepository.findById(voucher.id).get().balance.compareTo(BigDecimal("20000")) shouldBe 0

        // 2. 거래 취소
        val compensatingTxId = cancelService.cancel(result.transactionId)

        // 3. 잔액 복원 확인
        val restored = voucherRepository.findById(voucher.id).get()
        restored.balance.compareTo(BigDecimal("50000")) shouldBe 0
        restored.status shouldBe VoucherStatus.ACTIVE

        // 4. 보상 트랜잭션 확인: originalTransactionId 연결
        val compensatingTx = transactionRepository.findById(compensatingTxId).get()
        compensatingTx.originalTransactionId shouldBe result.transactionId

        // 5. 역방향 원장 엔트리 확인
        val compensatingEntries = ledgerRepository.findByTransactionId(compensatingTxId)
        compensatingEntries.size shouldBe 2
        // 원래 결제: debit MERCHANT_RECEIVABLE, credit VOUCHER_BALANCE
        // 보상: debit VOUCHER_BALANCE, credit MERCHANT_RECEIVABLE (역방향)
        val debit = compensatingEntries.first { it.side == LedgerEntrySide.DEBIT }
        val credit = compensatingEntries.first { it.side == LedgerEntrySide.CREDIT }
        debit.account.name shouldBe "VOUCHER_BALANCE"
        credit.account.name shouldBe "MERCHANT_RECEIVABLE"

        // 6. 원장 정합성
        verificationService.verify().isBalanced shouldBe true
    }

    @Test
    fun `withdrawal within 7 days should refund full amount`() {
        // 발행
        val voucher = fixtures.issueVoucher(memberId, regionId, BigDecimal("30000"))

        // 청약철회 (7일 이내)
        val withdrawn = withdrawalService.withdraw(voucher.id, memberId)
        withdrawn.status shouldBe VoucherStatus.WITHDRAWN
        withdrawn.balance.compareTo(BigDecimal.ZERO) shouldBe 0

        // 원장 정합성
        verificationService.verify().isBalanced shouldBe true
    }

    @Test
    fun `refund should be rejected when usage below 60 percent`() {
        // 발행 50,000 + 결제 20,000 (usage 40%)
        val voucher = fixtures.issueVoucher(memberId, regionId, BigDecimal("50000"))
        redemptionService.redeem(voucher.id, merchantId, BigDecimal("20000"))

        // 환불 시도 → 거절
        val ex = shouldThrow<BusinessException> {
            refundService.refund(voucher.id, memberId)
        }
        ex.errorCode shouldBe ErrorCode.REFUND_CONDITION_NOT_MET
    }

    @Test
    fun `settlement should calculate redemptions minus cancellations`() {
        // 발행 + 3건 결제
        val voucher = fixtures.issueVoucher(memberId, regionId, BigDecimal("50000"))
        val r1 = redemptionService.redeem(voucher.id, merchantId, BigDecimal("10000"))
        redemptionService.redeem(voucher.id, merchantId, BigDecimal("10000"))
        redemptionService.redeem(voucher.id, merchantId, BigDecimal("10000"))

        // 1건 취소
        cancelService.cancel(r1.transactionId)

        // 정산: 30,000 - 10,000 = 20,000
        val today = LocalDate.now()
        val settlement = settlementService.calculate(
            merchantId, today.withDayOfMonth(1), today.withDayOfMonth(today.lengthOfMonth())
        )
        settlement.totalAmount.compareTo(BigDecimal("20000")) shouldBe 0
    }

    @Test
    fun `audit logs should be created for critical operations`() {
        val initialCount = auditLogRepository.count()

        // 발행 → VOUCHER_ISSUED (CRITICAL)
        val voucher = fixtures.issueVoucher(memberId, regionId, BigDecimal("50000"))

        // 결제 → VOUCHER_REDEEMED (CRITICAL)
        redemptionService.redeem(voucher.id, merchantId, BigDecimal("30000"))

        // CRITICAL 감사 로그가 생성되었는지 확인
        val criticalLogs = auditLogRepository.findAll()
            .filter { it.severity == AuditSeverity.CRITICAL && it.createdAt.isAfter(java.time.LocalDateTime.now().minusMinutes(1)) }
        criticalLogs.shouldNotBeEmpty()
    }
}
