package com.commerce.voucher.application

import com.commerce.common.exception.BusinessException
import com.commerce.common.exception.ErrorCode
import com.commerce.ledger.application.LedgerService
import com.commerce.ledger.domain.AccountCode
import com.commerce.ledger.domain.LedgerEntryType
import com.commerce.region.application.RegionService
import com.commerce.region.domain.RegionStatus
import com.commerce.transaction.application.TransactionService
import com.commerce.transaction.domain.TransactionType
import com.commerce.voucher.domain.Voucher
import com.commerce.voucher.domain.VoucherCodeGenerator
import com.commerce.voucher.domain.event.VoucherIssuedEvent
import com.commerce.voucher.infrastructure.VoucherJpaRepository
import com.commerce.voucher.infrastructure.VoucherLockManager
import org.redisson.api.RScript
import org.redisson.api.RedissonClient
import org.redisson.client.codec.StringCodec
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import java.math.BigDecimal
import java.time.Duration
import java.time.LocalDateTime
import java.time.YearMonth

@Service
class VoucherIssueService(
    private val voucherRepository: VoucherJpaRepository,
    private val lockManager: VoucherLockManager,
    private val regionService: RegionService,
    private val codeGenerator: VoucherCodeGenerator,
    private val ledgerService: LedgerService,
    private val transactionService: TransactionService,
    private val redissonClient: RedissonClient,
    private val eventPublisher: ApplicationEventPublisher,
    private val transactionTemplate: TransactionTemplate,
) {

    /**
     * 상품권 발행.
     * 분산락을 트랜잭션 밖에서 잡아 락 해제 전 커밋을 보장한다.
     * (락 해제 ~ 커밋 사이 다른 스레드가 커밋 전 데이터를 읽는 문제 방지)
     */
    fun issue(memberId: Long, regionId: Long, faceValue: BigDecimal): Voucher {
        return lockManager.withMemberPurchaseLock(memberId) {
            transactionTemplate.execute { _ ->
                val region = regionService.getById(regionId)

                if (region.status != RegionStatus.ACTIVE)
                    throw BusinessException(ErrorCode.REGION_NOT_ACTIVE)

                // Check member purchase limit
                val totalPurchased = voucherRepository.sumFaceValueByMemberAndRegion(memberId, regionId)
                if (totalPurchased + faceValue > region.policy.purchaseLimitPerPerson)
                    throw BusinessException(ErrorCode.MEMBER_PURCHASE_LIMIT_EXCEEDED)

                // Check region monthly limit (Redis Lua script — atomic)
                checkRegionMonthlyLimit(regionId, faceValue, region.policy.monthlyIssuanceLimit)

                // Generate voucher code
                val code = codeGenerator.generate(region.regionCode)

                // Create voucher (ACTIVE)
                val voucher = voucherRepository.save(
                    Voucher(
                        voucherCode = code,
                        faceValue = faceValue,
                        balance = faceValue,
                        memberId = memberId,
                        regionId = regionId,
                        purchasedAt = LocalDateTime.now(),
                        expiresAt = LocalDateTime.now().plusMonths(6),
                    )
                )

                // Create transaction + ledger (synchronous, same DB tx)
                val tx = transactionService.create(
                    type = TransactionType.PURCHASE,
                    amount = faceValue,
                    voucherId = voucher.id,
                    memberId = memberId,
                )
                ledgerService.record(
                    debitAccount = AccountCode.VOUCHER_BALANCE,
                    creditAccount = AccountCode.MEMBER_CASH,
                    amount = faceValue,
                    transactionId = tx.id,
                    entryType = LedgerEntryType.PURCHASE,
                )
                tx.complete()

                // Publish event (audit log)
                eventPublisher.publishEvent(
                    VoucherIssuedEvent(voucher.id, memberId, regionId, faceValue)
                )

                voucher
            }!!
        }
    }

    private fun checkRegionMonthlyLimit(regionId: Long, amount: BigDecimal, limit: BigDecimal) {
        val key = "region:monthly:$regionId:${YearMonth.now()}"
        val amountLong = amount.longValueExact()
        val limitLong = limit.longValueExact()

        // Lua 스크립트로 INCRBY + 한도 검증을 원자적으로 수행
        // 한도 초과 시 자동 롤백 후 -1 반환, 성공 시 새 합계 반환
        // StringCodec: ARGV를 평문 문자열로 인코딩해야 Redis INCRBY가 정수로 해석 가능
        // (기본 바이너리 코덱은 Long을 바이너리로 직렬화해 "value is not an integer" 유발)
        val result = redissonClient.getScript(StringCodec.INSTANCE).eval<Long>(
            RScript.Mode.READ_WRITE,
            MONTHLY_LIMIT_CHECK_SCRIPT,
            RScript.ReturnType.INTEGER,
            listOf(key),
            amountLong.toString(), limitLong.toString(),
        )

        if (result == -1L) {
            throw BusinessException(ErrorCode.REGION_MONTHLY_LIMIT_EXCEEDED)
        }

        // TTL이 설정되지 않은 경우 월말 + 1일로 설정
        val counter = redissonClient.getAtomicLong(key)
        if (counter.remainTimeToLive() == -1L) {
            val endOfMonth = YearMonth.now().atEndOfMonth().plusDays(1)
            counter.expire(Duration.between(LocalDateTime.now(), endOfMonth.atStartOfDay()))
        }
    }

    companion object {
        /**
         * Redis Lua 스크립트: 원자적 한도 검증
         * KEYS[1] = 카운터 키, ARGV[1] = 증가량, ARGV[2] = 한도
         * 반환: 성공 시 새 합계, 한도 초과 시 -1
         */
        private const val MONTHLY_LIMIT_CHECK_SCRIPT = """
            local current = redis.call('INCRBY', KEYS[1], ARGV[1])
            if current > tonumber(ARGV[2]) then
                redis.call('DECRBY', KEYS[1], ARGV[1])
                return -1
            end
            return current
        """
    }
}
