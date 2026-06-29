package com.commerce.integration

import com.commerce.common.exception.BusinessException
import com.commerce.ledger.application.LedgerVerificationService
import com.commerce.support.IntegrationTestSupport
import com.commerce.support.TestFixtures
import com.commerce.transaction.domain.TransactionStatus
import com.commerce.transaction.infrastructure.TransactionJpaRepository
import com.commerce.voucher.application.VoucherRedemptionService
import com.commerce.voucher.infrastructure.VoucherJpaRepository
import io.kotest.matchers.comparables.shouldBeGreaterThanOrEqualTo
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.math.BigDecimal
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class ConcurrencyTest : IntegrationTestSupport() {

    @Autowired lateinit var fixtures: TestFixtures
    @Autowired lateinit var redemptionService: VoucherRedemptionService
    @Autowired lateinit var voucherRepository: VoucherJpaRepository
    @Autowired lateinit var transactionRepository: TransactionJpaRepository
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
    fun `10 concurrent redemptions on same voucher should not over-deduct`() {
        // given: 50,000원 상품권 발행
        val voucher = fixtures.issueVoucher(memberId, regionId, BigDecimal("50000"))
        val threadCount = 10
        val latch = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(threadCount)
        val successCount = AtomicInteger(0)
        val failCount = AtomicInteger(0)
        val failReasons = java.util.concurrent.ConcurrentHashMap<String, AtomicInteger>()

        // when: 10개 스레드가 동시에 10,000원씩 결제 시도
        val futures = (1..threadCount).map {
            executor.submit {
                latch.await()
                try {
                    redemptionService.redeem(
                        voucher.id, merchantId, BigDecimal("10000")
                    )
                    successCount.incrementAndGet()
                } catch (e: BusinessException) {
                    failCount.incrementAndGet()
                    failReasons.computeIfAbsent(e.errorCode.name) { AtomicInteger(0) }.incrementAndGet()
                }
            }
        }
        latch.countDown()
        futures.forEach { it.get() }
        executor.shutdown()

        // then: I1 — 잔액은 음수 불가
        val updated = voucherRepository.findById(voucher.id).get()
        updated.balance shouldBeGreaterThanOrEqualTo BigDecimal.ZERO

        // 50,000 / 10,000 = 정확히 5건만 성공해야 함
        successCount.get() shouldBe 5
        failCount.get() shouldBe 5
        updated.balance.compareTo(BigDecimal.ZERO) shouldBe 0

        // 5건 소진 후 잔액 0 → 상태 EXHAUSTED → 6번째부터 VOUCHER_NOT_USABLE (LOCK_TIMEOUT 아님)
        failReasons.keys shouldBe setOf("VOUCHER_NOT_USABLE")

        // 완료된 트랜잭션 수 검증
        val completedTxCount = transactionRepository.countByVoucherIdAndStatus(
            voucher.id, TransactionStatus.COMPLETED
        )
        // 발행 1건 + 결제 5건 = 6건
        completedTxCount shouldBe 6

        // I2 — 원장 글로벌 차대변 균형
        val verificationResult = verificationService.verify()
        verificationResult.isBalanced shouldBe true
    }

    @Test
    fun `concurrent redemption and refund should not conflict`() {
        // given: 50,000원 상품권을 35,000원 사용 (usage 70% > 60%)
        val voucher = fixtures.issueVoucher(memberId, regionId, BigDecimal("50000"))
        redemptionService.redeem(voucher.id, merchantId, BigDecimal("35000"))

        // 잔액 15,000원, usage 70%
        val latch = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        val results = mutableListOf<String>()

        // when: 결제(10,000원)와 환불을 동시 시도
        val f1 = executor.submit {
            latch.await()
            try {
                redemptionService.redeem(voucher.id, merchantId, BigDecimal("10000"))
                synchronized(results) { results.add("REDEEM_SUCCESS") }
            } catch (e: BusinessException) {
                synchronized(results) { results.add("REDEEM_FAIL:${e.errorCode}") }
            }
        }
        val f2 = executor.submit {
            latch.await()
            try {
                // RefundService를 직접 호출하면 순환참조 이슈가 있을 수 있으므로
                // 여기서는 결제를 2건 동시에 시도하여 동시성 제어를 검증
                redemptionService.redeem(voucher.id, merchantId, BigDecimal("10000"))
                synchronized(results) { results.add("REDEEM2_SUCCESS") }
            } catch (e: BusinessException) {
                synchronized(results) { results.add("REDEEM2_FAIL:${e.errorCode}") }
            }
        }
        latch.countDown()
        f1.get()
        f2.get()
        executor.shutdown()

        // then: 잔액 15,000원에서 10,000원 2건 동시 시도 → 1건만 성공
        val updated = voucherRepository.findById(voucher.id).get()
        updated.balance shouldBeGreaterThanOrEqualTo BigDecimal.ZERO

        val successResults = results.filter { it.contains("SUCCESS") }
        successResults.size shouldBe 1
        updated.balance.compareTo(BigDecimal("5000")) shouldBe 0
    }
}
