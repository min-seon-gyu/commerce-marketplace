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
