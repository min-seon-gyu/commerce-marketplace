package com.commerce.voucher.infrastructure

import com.commerce.common.exception.BusinessException
import com.commerce.common.exception.ErrorCode
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import org.redisson.api.RedissonClient
import org.springframework.stereotype.Component
import java.util.concurrent.TimeUnit

@Component
class VoucherLockManager(
    private val redissonClient: RedissonClient,
    private val meterRegistry: MeterRegistry,
) {

    fun <T> withVoucherLock(voucherId: Long, action: () -> T): T =
        withLock("voucher:$voucherId", action)

    fun <T> withMemberPurchaseLock(memberId: Long, action: () -> T): T =
        withLock("member:purchase:$memberId", action)

    private fun <T> withLock(key: String, action: () -> T): T {
        val lock = redissonClient.getLock(key)
        val timer = Timer.start(meterRegistry)
        val acquired = lock.tryLock(5, 10, TimeUnit.SECONDS)
        timer.stop(meterRegistry.timer("lock.acquisition.duration", "key", key.substringBefore(':')))

        if (!acquired) {
            meterRegistry.counter("lock.acquisition.timeout", "key", key.substringBefore(':')).increment()
            throw BusinessException(ErrorCode.LOCK_ACQUISITION_FAILED)
        }
        try {
            return action()
        } finally {
            if (lock.isHeldByCurrentThread) lock.unlock()
        }
    }
}
