package com.commerce.integration

import com.commerce.common.exception.BusinessException
import com.commerce.common.exception.ErrorCode
import com.commerce.support.IntegrationTestSupport
import com.commerce.support.TestFixtures
import com.commerce.voucher.application.VoucherRedemptionService
import com.commerce.voucher.application.VoucherRefundService
import com.commerce.voucher.application.VoucherWithdrawalService
import com.commerce.voucher.domain.VoucherStatus
import com.commerce.voucher.infrastructure.VoucherJpaRepository
import com.commerce.seller.application.SettlementService
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

class BoundaryTest : IntegrationTestSupport() {

    @Autowired lateinit var fixtures: TestFixtures
    @Autowired lateinit var redemptionService: VoucherRedemptionService
    @Autowired lateinit var refundService: VoucherRefundService
    @Autowired lateinit var withdrawalService: VoucherWithdrawalService
    @Autowired lateinit var settlementService: SettlementService
    @Autowired lateinit var voucherRepository: VoucherJpaRepository

    private var regionId: Long = 0
    private var memberId: Long = 0
    private var sellerId: Long = 0

    @BeforeEach
    fun setup() {
        val region = fixtures.createRegion(code = UUID.randomUUID().toString().take(2).uppercase())
        val member = fixtures.createMember()
        val sellerOwner = fixtures.createMember()
        val seller = fixtures.createSeller(region, sellerOwner)
        regionId = region.id
        memberId = member.id
        sellerId = seller.id
    }

    @Nested
    inner class RefundThreshold {

        @Test
        fun `should allow refund when usage is exactly 60 percent`() {
            // 50,000원 중 30,000원 사용 = 정확히 60%
            val voucher = fixtures.issueVoucher(memberId, regionId, BigDecimal("50000"))
            redemptionService.redeem(voucher.id, sellerId, BigDecimal("30000"))

            val refunded = refundService.refund(voucher.id, memberId)
            refunded.status shouldBe VoucherStatus.REFUNDED
        }

        @Test
        fun `should reject refund when usage is 59 percent`() {
            // 50,000원 중 29,500원 사용 = 59%
            val voucher = fixtures.issueVoucher(memberId, regionId, BigDecimal("50000"))
            redemptionService.redeem(voucher.id, sellerId, BigDecimal("29500"))

            val ex = shouldThrow<BusinessException> {
                refundService.refund(voucher.id, memberId)
            }
            ex.errorCode shouldBe ErrorCode.REFUND_CONDITION_NOT_MET
        }

        @Test
        fun `should allow refund when usage is 61 percent`() {
            // 50,000원 중 30,500원 사용 = 61%
            val voucher = fixtures.issueVoucher(memberId, regionId, BigDecimal("50000"))
            redemptionService.redeem(voucher.id, sellerId, BigDecimal("30500"))

            val refunded = refundService.refund(voucher.id, memberId)
            refunded.status shouldBe VoucherStatus.REFUNDED
        }
    }

    @Nested
    inner class WithdrawalPeriod {

        @Test
        fun `should allow withdrawal on day 7`() {
            // 정확히 7일 전 구매 (아직 7일 이내)
            val voucher = fixtures.issueVoucher(memberId, regionId, BigDecimal("30000"))
            fixtures.forcePurchasedAt(voucher.id, LocalDateTime.now().minusDays(6).minusHours(23))

            val withdrawn = withdrawalService.withdraw(voucher.id, memberId)
            withdrawn.status shouldBe VoucherStatus.WITHDRAWN
        }

        @Test
        fun `should reject withdrawal on day 8`() {
            // 8일 전 구매 (7일 초과)
            val voucher = fixtures.issueVoucher(memberId, regionId, BigDecimal("30000"))
            fixtures.forcePurchasedAt(voucher.id, LocalDateTime.now().minusDays(8))

            val ex = shouldThrow<BusinessException> {
                withdrawalService.withdraw(voucher.id, memberId)
            }
            ex.errorCode shouldBe ErrorCode.WITHDRAWAL_PERIOD_EXPIRED
        }
    }

    @Nested
    inner class SettlementDuplicate {

        @Test
        fun `should reject duplicate settlement for same period`() {
            // 결제 1건 생성
            val voucher = fixtures.issueVoucher(memberId, regionId, BigDecimal("50000"))
            redemptionService.redeem(voucher.id, sellerId, BigDecimal("10000"))

            val today = LocalDate.now()
            val start = today.withDayOfMonth(1)
            val end = today.withDayOfMonth(today.lengthOfMonth())

            // 1차 정산 성공
            settlementService.calculate(sellerId, start, end)

            // 2차 정산 시도 → 거절
            val ex = shouldThrow<BusinessException> {
                settlementService.calculate(sellerId, start, end)
            }
            ex.errorCode shouldBe ErrorCode.INVALID_INPUT
        }
    }
}
