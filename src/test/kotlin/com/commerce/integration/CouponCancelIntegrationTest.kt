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
    @Autowired lateinit var ledgerService: LedgerService
    @Autowired lateinit var pointAccountRepository: PointAccountJpaRepository
    @Autowired lateinit var pointTransactionRepository: PointTransactionJpaRepository

    private var regionId: Long = 0
    private var memberId: Long = 0
    private var sellerId: Long = 0

    @BeforeEach
    fun setup() {
        val region = fixtures.createRegion(code = UUID.randomUUID().toString().take(2).uppercase())
        val member = fixtures.createMember()
        val seller = fixtures.createSeller(region, fixtures.createMember())
        regionId = region.id
        memberId = member.id
        sellerId = seller.id
    }

    @Test
    fun `cancelling a coupon-applied redeem reverses both ledger pairs and restores everything`() {
        val voucher = fixtures.issueVoucher(memberId, regionId, BigDecimal("50000"))
        val promotion = fixtures.createPromotion(
            discountType = DiscountType.FIXED, discountValue = BigDecimal("3000"),
            budgetLimit = BigDecimal("1000000"),
        )
        val coupon = fixtures.issueCoupon(promotion.id, memberId)

        val result = orchestrator.redeem(voucher.id, sellerId, BigDecimal("10000"), coupon.id)
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
        // 글로벌 정합성 유지 (포인트 잔액 일치 포함)
        val verify = verificationService.verify()
        verify.isBalanced shouldBe true
        verify.pointBalanceMatches shouldBe true

        // I1: 적립 포인트(T-D=7000 * 0.01 = 70)도 역분개 → balance 0, CANCEL PointTransaction 적재
        pointAccountRepository.findByMemberId(memberId)!!.balance.compareTo(BigDecimal.ZERO) shouldBe 0
        pointTransactionRepository.findBySourceTransactionId(result.transactionId).count {
            it.type == PointTransactionType.CANCEL && it.amount.compareTo(BigDecimal("70")) == 0
        } shouldBe 1

        // Fix 2 + I1: 보상 취소 tx의 정확한 역분개 행 직접 검증 (T=10000, D=3000, voucherCharged=7000)
        // cancelWithCoupon 내부: pair1(voucherCharged=7000) + pair2(discount=3000) + 포인트 역분개(70) → 6 entries
        val cancelEntries = ledgerService.getEntriesByTransactionId(compensatingTxId)
        cancelEntries.size shouldBe 6
        // 쌍1 역분개: DEBIT VOUCHER_BALANCE 7000 / CREDIT MERCHANT_RECEIVABLE 7000 (CANCELLATION)
        cancelEntries.count {
            it.account == AccountCode.VOUCHER_BALANCE &&
            it.side == LedgerEntrySide.DEBIT &&
            it.amount.compareTo(BigDecimal("7000")) == 0 &&
            it.entryType == LedgerEntryType.CANCELLATION
        } shouldBe 1
        cancelEntries.count {
            it.account == AccountCode.MERCHANT_RECEIVABLE &&
            it.side == LedgerEntrySide.CREDIT &&
            it.amount.compareTo(BigDecimal("7000")) == 0 &&
            it.entryType == LedgerEntryType.CANCELLATION
        } shouldBe 1
        // 쌍2 역분개: DEBIT PROMOTION_FUNDING 3000 / CREDIT MERCHANT_RECEIVABLE 3000 (CANCELLATION)
        // — PROMOTION_FUNDING 역분개를 isBalanced 외에 직접 핀(pin)
        cancelEntries.count {
            it.account == AccountCode.PROMOTION_FUNDING &&
            it.side == LedgerEntrySide.DEBIT &&
            it.amount.compareTo(BigDecimal("3000")) == 0 &&
            it.entryType == LedgerEntryType.CANCELLATION
        } shouldBe 1
        cancelEntries.count {
            it.account == AccountCode.MERCHANT_RECEIVABLE &&
            it.side == LedgerEntrySide.CREDIT &&
            it.amount.compareTo(BigDecimal("3000")) == 0 &&
            it.entryType == LedgerEntryType.CANCELLATION
        } shouldBe 1
        // I1 포인트 적립 역분개: DEBIT POINT_FUNDING 70 / CREDIT POINT_BALANCE 70 (CANCELLATION)
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
    }

    /**
     * Fix 1: 전액 쿠폰 보전(T-D == 0) 취소 엣지 케이스.
     *
     * discountValue == orderTotal 이므로 클램프 후 discount = min(D, T) = T.
     * → voucherCharged = T - D = 0: 바우처 차감 없음, VOUCHER_BALANCE 분개 없음.
     * redeem pair1(바우처분) 생략 → cancel pair1 역분개도 생략.
     * redeem pair2(보조분): DEBIT MERCHANT_RECEIVABLE / CREDIT PROMOTION_FUNDING (D=T).
     * cancel pair2 역분개: DEBIT PROMOTION_FUNDING / CREDIT MERCHANT_RECEIVABLE (D=T).
     *
     * 검증: 바우처 잔액은 redeem+cancel 전후 불변; VOUCHER_BALANCE 분개 행 없음;
     *        예산 반환, 쿠폰 CANCELLED, isBalanced.
     */
    @Test
    fun `full-discount cancel (T-D == 0) voucher balance is unchanged and no VOUCHER_BALANCE ledger leg exists`() {
        val orderTotal = BigDecimal("10000")
        val voucher = fixtures.issueVoucher(memberId, regionId, BigDecimal("50000"))
        // discountValue == orderTotal → clamp: discount = min(10000, 10000) = 10000, voucherCharged = 0
        val promotion = fixtures.createPromotion(
            discountType = DiscountType.FIXED,
            discountValue = orderTotal,
            budgetLimit = BigDecimal("1000000"),
        )
        val coupon = fixtures.issueCoupon(promotion.id, memberId)

        // Redeem — voucherCharged = 0, 바우처 잔액 불변
        val result = orchestrator.redeem(voucher.id, sellerId, orderTotal, coupon.id)
        voucherRepository.findById(voucher.id).get().balance.compareTo(BigDecimal("50000")) shouldBe 0
        budgetManager.consumed(promotion.id) shouldBe 10000L

        // Redeem tx에는 VOUCHER_BALANCE 분개 행이 없어야 함 (only PROMOTION_FUNDING/MERCHANT_RECEIVABLE)
        val redeemEntries = ledgerService.getEntriesByTransactionId(result.transactionId)
        redeemEntries.none { it.account == AccountCode.VOUCHER_BALANCE } shouldBe true
        redeemEntries.count {
            it.account == AccountCode.PROMOTION_FUNDING && it.side == LedgerEntrySide.CREDIT
        } shouldBe 1
        redeemEntries.count {
            it.account == AccountCode.MERCHANT_RECEIVABLE && it.side == LedgerEntrySide.DEBIT
        } shouldBe 1

        // Cancel
        val compensatingTxId = cancelService.cancel(result.transactionId)

        // 바우처 잔액 여전히 불변 (T-D=0이므로 복원 대상도 0)
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
        // 글로벌 정합성 유지 (pair1 없이 pair2만으로도 D=C)
        verificationService.verify().isBalanced shouldBe true

        // 보상 취소 tx에도 VOUCHER_BALANCE 분개 행 없음 (pair1 역분개 생략)
        val cancelEntries = ledgerService.getEntriesByTransactionId(compensatingTxId)
        cancelEntries.none { it.account == AccountCode.VOUCHER_BALANCE } shouldBe true
        // pair2 역분개만 존재: DEBIT PROMOTION_FUNDING / CREDIT MERCHANT_RECEIVABLE
        cancelEntries.count {
            it.account == AccountCode.PROMOTION_FUNDING &&
            it.side == LedgerEntrySide.DEBIT &&
            it.amount.compareTo(orderTotal) == 0 &&
            it.entryType == LedgerEntryType.CANCELLATION
        } shouldBe 1
        cancelEntries.count {
            it.account == AccountCode.MERCHANT_RECEIVABLE &&
            it.side == LedgerEntrySide.CREDIT &&
            it.amount.compareTo(orderTotal) == 0 &&
            it.entryType == LedgerEntryType.CANCELLATION
        } shouldBe 1
    }
}
