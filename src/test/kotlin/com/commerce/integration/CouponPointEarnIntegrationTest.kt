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

        // 1) 포인트 잔액 = (T−D)의 1% = 70 (10,000의 1%=100 아님)
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
