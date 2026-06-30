package com.commerce.integration

import com.commerce.ledger.domain.AccountCode
import com.commerce.ledger.infrastructure.LedgerJpaRepository
import com.commerce.point.application.PointEarnService
import com.commerce.point.infrastructure.PointTransactionJpaRepository
import com.commerce.support.IntegrationTestSupport
import com.commerce.support.TestFixtures
import com.commerce.transaction.domain.TransactionStatus
import com.commerce.transaction.infrastructure.TransactionJpaRepository
import com.commerce.voucher.application.VoucherRedemptionService
import com.commerce.voucher.infrastructure.VoucherJpaRepository
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.Mockito.doThrow
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.mock.mockito.MockBean
import java.math.BigDecimal
import java.util.UUID

/**
 * Proves that earn() runs INSIDE the redeem transaction:
 * if PointEarnService.earn() throws, the entire redeem rolls back
 * (voucher balance unchanged, no REDEMPTION ledger entries, no PointTransaction).
 */
class PointEarnRollbackTest : IntegrationTestSupport() {

    /**
     * Kotlin + Mockito 5 null-safety workaround (no mockito-kotlin available).
     *
     * Mockito 5 annotates all ArgumentMatchers methods with @NotNull/@Nullable which causes
     * Kotlin (in -Xjsr305=strict mode) to add null-assertion checks at call sites.
     * Wrapping `any()` with an unchecked cast to a non-null T hides the null from Kotlin:
     * - Mockito still registers the ANY matcher in its thread-local stack ✓
     * - The declared return type T (non-null) prevents a Kotlin call-site null check ✓
     * - The actual null value reaches the CGLIB proxy, which ignores it (uses the matcher) ✓
     */
    @Suppress("UNCHECKED_CAST")
    private fun <T> anyMatcher(): T = org.mockito.ArgumentMatchers.any() as T

    @MockBean
    lateinit var pointEarnService: PointEarnService

    @Autowired lateinit var fixtures: TestFixtures
    @Autowired lateinit var redemptionService: VoucherRedemptionService
    @Autowired lateinit var voucherRepository: VoucherJpaRepository
    @Autowired lateinit var transactionRepository: TransactionJpaRepository
    @Autowired lateinit var ledgerRepository: LedgerJpaRepository
    @Autowired lateinit var pointTransactionRepository: PointTransactionJpaRepository

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
    fun `earn failure rolls back entire redeem - voucher balance unchanged and no ledger or point entries`() {
        // Arrange: voucher with a known balance.
        // Note: issueVoucher() also creates a PURCHASE Transaction (COMPLETED) for this voucherId,
        // so we capture pre-redeem counts as the baseline.
        val voucher = fixtures.issueVoucher(memberId, regionId, BigDecimal("50000"))
        val initialBalance = voucher.balance
        val completedTxCountBefore = transactionRepository.countByVoucherIdAndStatus(voucher.id, TransactionStatus.COMPLETED)
        val ledgerNetBefore = ledgerRepository.netBalanceByVoucherAndAccount(voucher.id, AccountCode.VOUCHER_BALANCE)

        // Stub earn() to throw, simulating a downstream failure inside the redeem transaction.
        // anyMatcher() is a null-safe Mockito ANY wrapper (see above).
        doThrow(RuntimeException("boom")).`when`(pointEarnService)
            .earn(anyLong(), anyMatcher(), anyLong())

        // Act: redeem must propagate the exception
        assertThrows<Exception> {
            redemptionService.redeem(voucher.id, merchantId, BigDecimal("20000"))
        }

        // Assert ROLLBACK:
        // 1) Voucher balance is UNCHANGED — the deduction was rolled back
        val reloadedVoucher = voucherRepository.findById(voucher.id).get()
        reloadedVoucher.balance.compareTo(initialBalance) shouldBe 0

        // 2) No new REDEMPTION transaction committed — COMPLETED count must not have increased.
        //    (completedTxCountBefore == 1: the PURCHASE tx from issuance)
        transactionRepository.countByVoucherIdAndStatus(voucher.id, TransactionStatus.COMPLETED) shouldBe completedTxCountBefore

        // 2b) No PENDING transaction left over (would indicate a partial/uncommitted REDEMPTION tx)
        transactionRepository.countByVoucherIdAndStatus(voucher.id, TransactionStatus.PENDING) shouldBe 0L

        // 3) No REDEMPTION ledger entries posted — net VOUCHER_BALANCE impact must be unchanged
        ledgerRepository.netBalanceByVoucherAndAccount(voucher.id, AccountCode.VOUCHER_BALANCE)
            .compareTo(ledgerNetBefore) shouldBe 0

        // 4) No PointTransaction was persisted for this member
        pointTransactionRepository.findByMemberIdOrderByCreatedAtDesc(memberId).size shouldBe 0
    }
}
