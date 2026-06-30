package com.commerce.voucher.application

import com.commerce.region.infrastructure.RegionJpaRepository
import com.commerce.voucher.infrastructure.VoucherJpaRepository
import org.redisson.api.RedissonClient
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.LocalDateTime
import java.time.YearMonth

/**
 * Redis Region 월 발행한도 카운터를 DB 기준으로 동기화.
 * Redis 재시작 시 카운터가 0으로 리셋되는 문제를 방지한다.
 */
@Component
class RegionCounterSyncScheduler(
    private val regionRepository: RegionJpaRepository,
    private val voucherRepository: VoucherJpaRepository,
    private val redissonClient: RedissonClient,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** 매시 정각에 실행 */
    @Scheduled(cron = "0 0 * * * *")
    fun syncRegionMonthlyCounters() {
        val currentMonth = YearMonth.now()
        val regions = regionRepository.findAll()

        regions.forEach { region ->
            try {
                val key = "region:monthly:${region.id}:$currentMonth"
                val dbTotal = voucherRepository.sumFaceValueByRegionAndMonth(
                    region.id,
                    currentMonth.atDay(1).atStartOfDay(),
                    currentMonth.atEndOfMonth().plusDays(1).atStartOfDay(),
                )

                val counter = redissonClient.getAtomicLong(key)
                val currentValue = counter.get()

                // DB 값보다 작을 때만 보정 (Redis 재시작 등으로 유실된 경우)
                // DB 값보다 큰 경우는 동기화 이후 신규 발행이 발생한 것이므로 덮어쓰지 않음
                if (currentValue < dbTotal.toLong()) {
                    counter.set(dbTotal.toLong())
                    log.info("Region {} monthly counter corrected: {} -> {}", region.regionCode, currentValue, dbTotal)
                }

                // TTL: 월 말 + 1일
                if (counter.remainTimeToLive() == -1L) {
                    val endOfMonth = currentMonth.atEndOfMonth().plusDays(1)
                    counter.expire(Duration.between(LocalDateTime.now(), endOfMonth.atStartOfDay()))
                }

                log.debug("Region {} monthly counter synced: current={}, db={}", region.regionCode, counter.get(), dbTotal)
            } catch (e: Exception) {
                log.error("Failed to sync region {} counter: {}", region.regionCode, e.message)
            }
        }
    }
}
