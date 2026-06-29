package com.commerce.integration

import com.commerce.ledger.application.LedgerService
import com.commerce.ledger.application.LedgerVerificationService
import com.commerce.ledger.domain.AccountCode
import com.commerce.ledger.domain.LedgerEntrySide
import com.commerce.ledger.domain.LedgerEntryType
import com.commerce.point.domain.PointTransactionType
import com.commerce.point.infrastructure.PointAccountJpaRepository
import com.commerce.point.infrastructure.PointTransactionJpaRepository
import com.commerce.promotion.application.RedemptionOrchestrator
import com.commerce.promotion.domain.DiscountType
import com.commerce.support.IntegrationTestSupport
import com.commerce.support.TestFixtures
import com.commerce.transaction.application.TransactionCancelService
import com.commerce.voucher.application.VoucherRedemptionService
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.math.BigDecimal
import java.util.UUID

/**
 * I1 fix: 취소(보상) 트랜잭션이 redemption으로 적립된 포인트까지 역분개하는지 검증한다.
 *
 * 결함: redeem(적립) → cancel(바우처 환불) 후에도 포인트가 그대로 남아 farm 가능.
 * 수정: cancel 트랜잭션 내부에서 POINT_EARN을 역분개(CANCELLATION)하고 PointAccount.balance를 차감.
 *
 * 불변식: cancel 후 isBalanced && pointBalanceMatches == true,
 *        PointAccount.balance는 적립 전 값(여기서는 0)으로 복귀,
 *        원 EARN 행은 보존(immutable)하고 CANCELLATION 원장쌍 + CANCEL PointTransaction을 추가.
 */
class PointCancelIntegrationTest : IntegrationTestSupport() {

    @Autowired lateinit var fixtures: TestFixtures
    @Autowired lateinit var redemptionService: VoucherRedemptionService
    @Autowired lateinit var orchestrator: RedemptionOrchestrator
    @Autowired lateinit var cancelService: TransactionCancelService
    @Autowired lateinit var pointAccountRepository: PointAccountJpaRepository
    @Autowired lateinit var pointTransactionRepository: PointTransactionJpaRepository
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
    fun `plain redeem earns points then cancel reverses them and restores point balance`() {
        // 적립률 0.01: 30,000 결제 → 300 포인트 적립
        val voucher = fixtures.issueVoucher(memberId, regionId, BigDecimal("50000"))
        val result = redemptionService.redeem(voucher.id, merchantId, BigDecimal("30000"))

        // 적립 직후: balance 300
        pointAccountRepository.findByMemberId(memberId)!!.balance.compareTo(BigDecimal("300")) shouldBe 0

        // 취소 → 포인트 역분개
        val compensatingTxId = cancelService.cancel(result.transactionId)

        // 1) PointAccount.balance 적립 전 값(0)으로 복귀
        pointAccountRepository.findByMemberId(memberId)!!.balance.compareTo(BigDecimal.ZERO) shouldBe 0

        // 2) CANCEL PointTransaction 적재(원 EARN 행 보존 + CANCEL 추가)
        val pointTxs = pointTransactionRepository.findBySourceTransactionId(result.transactionId)
        pointTxs.count { it.type == PointTransactionType.EARN } shouldBe 1
        pointTxs.count {
            it.type == PointTransactionType.CANCEL && it.amount.compareTo(BigDecimal("300")) == 0
        } shouldBe 1

        // 3) 역분개 원장쌍: DEBIT POINT_FUNDING 300 / CREDIT POINT_BALANCE 300 (CANCELLATION)
        val cancelEntries = ledgerService.getEntriesByTransactionId(compensatingTxId)
        cancelEntries.count {
            it.account == AccountCode.POINT_FUNDING &&
                it.side == LedgerEntrySide.DEBIT &&
                it.amount.compareTo(BigDecimal("300")) == 0 &&
                it.entryType == LedgerEntryType.CANCELLATION
        } shouldBe 1
        cancelEntries.count {
            it.account == AccountCode.POINT_BALANCE &&
                it.side == LedgerEntrySide.CREDIT &&
                it.amount.compareTo(BigDecimal("300")) == 0 &&
                it.entryType == LedgerEntryType.CANCELLATION
        } shouldBe 1

        // 4) 글로벌 정합성: isBalanced && pointBalanceMatches
        val verify = verificationService.verify()
        verify.isBalanced shouldBe true
        verify.pointBalanceMatches shouldBe true
    }

    @Test
    fun `coupon redeem earns on T-D then cancel reverses the T-D-based points`() {
        // T=10,000, D=3,000 → voucherCharged=7,000 → 적립 70
        val voucher = fixtures.issueVoucher(memberId, regionId, BigDecimal("50000"))
        val promotion = fixtures.createPromotion(
            discountType = DiscountType.FIXED, discountValue = BigDecimal("3000"),
            budgetLimit = BigDecimal("1000000"),
        )
        val coupon = fixtures.issueCoupon(promotion.id, memberId)

        val result = orchestrator.redeem(voucher.id, merchantId, BigDecimal("10000"), coupon.id)
        pointAccountRepository.findByMemberId(memberId)!!.balance.compareTo(BigDecimal("70")) shouldBe 0

        val compensatingTxId = cancelService.cancel(result.transactionId)

        // 포인트 70 역분개 → balance 0
        pointAccountRepository.findByMemberId(memberId)!!.balance.compareTo(BigDecimal.ZERO) shouldBe 0

        val pointTxs = pointTransactionRepository.findBySourceTransactionId(result.transactionId)
        pointTxs.count {
            it.type == PointTransactionType.CANCEL && it.amount.compareTo(BigDecimal("70")) == 0
        } shouldBe 1

        val cancelEntries = ledgerService.getEntriesByTransactionId(compensatingTxId)
        cancelEntries.count {
            it.account == AccountCode.POINT_FUNDING &&
                it.side == LedgerEntrySide.DEBIT &&
                it.amount.compareTo(BigDecimal("70")) == 0 &&
                it.entryType == LedgerEntryType.CANCELLATION
        } shouldBe 1
        cancelEntries.count {
            it.account == AccountCode.POINT_BALANCE &&
                it.side == LedgerEntrySide.CREDIT &&
                it.amount.compareTo(BigDecimal("70")) == 0 &&
                it.entryType == LedgerEntryType.CANCELLATION
        } shouldBe 1

        val verify = verificationService.verify()
        verify.isBalanced shouldBe true
        verify.pointBalanceMatches shouldBe true
    }

    @Test
    fun `zero-earn redeem (full discount) cancel posts no point reversal and stays balanced`() {
        // T=10,000, D=10,000(클램프) → voucherCharged=0 → 적립 0 → 취소 시 포인트 역분개 없음
        val orderTotal = BigDecimal("10000")
        val voucher = fixtures.issueVoucher(memberId, regionId, BigDecimal("50000"))
        val promotion = fixtures.createPromotion(
            discountType = DiscountType.FIXED, discountValue = orderTotal,
            budgetLimit = BigDecimal("1000000"),
        )
        val coupon = fixtures.issueCoupon(promotion.id, memberId)

        val result = orchestrator.redeem(voucher.id, merchantId, orderTotal, coupon.id)
        // 적립 0 → PointAccount 미생성(또는 0)
        (pointAccountRepository.findByMemberId(memberId)?.balance ?: BigDecimal.ZERO)
            .compareTo(BigDecimal.ZERO) shouldBe 0

        val compensatingTxId = cancelService.cancel(result.transactionId)

        // 포인트 역분개 행/거래 없음
        pointTransactionRepository.findBySourceTransactionId(result.transactionId)
            .none { it.type == PointTransactionType.CANCEL } shouldBe true
        val cancelEntries = ledgerService.getEntriesByTransactionId(compensatingTxId)
        cancelEntries.none {
            it.account == AccountCode.POINT_FUNDING || it.account == AccountCode.POINT_BALANCE
        } shouldBe true

        val verify = verificationService.verify()
        verify.isBalanced shouldBe true
        verify.pointBalanceMatches shouldBe true
    }
}
