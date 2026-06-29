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

        // 1) 포인트 계좌 잔액 = 200원
        val account = pointAccountRepository.findByMemberId(memberId)
        account.shouldNotBeNull()
        account.balance.compareTo(BigDecimal("200")) shouldBe 0

        // 2) EARN 거래 1건, 원 거래 txId에 연결
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

        // 4) 전역 원장 정합성
        val verification = verificationService.verify()
        verification.isBalanced shouldBe true
    }
}
