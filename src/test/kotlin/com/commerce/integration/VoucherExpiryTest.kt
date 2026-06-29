package com.commerce.integration

import com.commerce.ledger.application.LedgerService
import com.commerce.ledger.application.LedgerVerificationService
import com.commerce.ledger.domain.AccountCode
import com.commerce.ledger.domain.LedgerEntryType
import com.commerce.support.IntegrationTestSupport
import com.commerce.support.TestFixtures
import com.commerce.transaction.application.TransactionService
import com.commerce.transaction.domain.TransactionType
import com.commerce.voucher.application.VoucherExpiryProcessor
import com.commerce.voucher.application.VoucherRedemptionService
import com.commerce.voucher.domain.VoucherStatus
import com.commerce.voucher.domain.event.VoucherExpiredEvent
import com.commerce.voucher.infrastructure.VoucherJpaRepository
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.ApplicationEventPublisher
import java.math.BigDecimal
import java.time.LocalDateTime
import java.util.UUID

class VoucherExpiryTest : IntegrationTestSupport() {

    @Autowired lateinit var fixtures: TestFixtures
    @Autowired lateinit var expiryProcessor: VoucherExpiryProcessor
    @Autowired lateinit var voucherRepository: VoucherJpaRepository
    @Autowired lateinit var redemptionService: VoucherRedemptionService
    @Autowired lateinit var verificationService: LedgerVerificationService

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
    fun `should expire voucher and move remaining balance to EXPIRED account`() {
        val voucher = fixtures.issueVoucher(memberId, regionId, BigDecimal("50000"))
        // expiresAt을 과거로 변경
        fixtures.forceExpireVoucher(voucher.id)

        // REQUIRES_NEW 트랜잭션으로 만료 처리
        expiryProcessor.processExpiry(voucher.id)

        val updated = voucherRepository.findById(voucher.id).get()
        updated.status shouldBe VoucherStatus.EXPIRED
        updated.balance.compareTo(BigDecimal.ZERO) shouldBe 0
    }

    @Test
    fun `should expire partially used voucher and move remaining balance`() {
        val voucher = fixtures.issueVoucher(memberId, regionId, BigDecimal("50000"))
        redemptionService.redeem(voucher.id, merchantId, BigDecimal("30000"))
        fixtures.forceExpireVoucher(voucher.id)

        expiryProcessor.processExpiry(voucher.id)

        val updated = voucherRepository.findById(voucher.id).get()
        updated.status shouldBe VoucherStatus.EXPIRED
        updated.balance.compareTo(BigDecimal.ZERO) shouldBe 0
    }

    @Test
    fun `expired voucher ledger should be balanced`() {
        val voucher = fixtures.issueVoucher(memberId, regionId, BigDecimal("50000"))
        redemptionService.redeem(voucher.id, merchantId, BigDecimal("20000"))
        fixtures.forceExpireVoucher(voucher.id)

        expiryProcessor.processExpiry(voucher.id)

        val result = verificationService.verify()
        result.isBalanced shouldBe true
    }
}
