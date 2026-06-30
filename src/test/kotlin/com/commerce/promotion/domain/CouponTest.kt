package com.commerce.promotion.domain

import com.commerce.common.exception.BusinessException
import com.commerce.common.exception.ErrorCode
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.LocalDateTime

class CouponTest {

    private fun coupon(
        status: CouponStatus = CouponStatus.ISSUED,
        expiresAt: LocalDateTime = LocalDateTime.now().plusDays(1),
    ) = Coupon(promotionId = 1L, memberId = 10L, expiresAt = expiresAt, status = status)

    @Test
    fun `redeem transitions ISSUED to REDEEMED`() {
        val c = coupon()
        c.redeem()
        c.status shouldBe CouponStatus.REDEEMED
    }

    @Test
    fun `redeem on a non-ISSUED coupon throws COUPON_ALREADY_USED`() {
        shouldThrow<BusinessException> { coupon(status = CouponStatus.REDEEMED).redeem() }
            .errorCode shouldBe ErrorCode.COUPON_ALREADY_USED
    }

    @Test
    fun `redeem on an expired coupon throws COUPON_EXPIRED`() {
        shouldThrow<BusinessException> {
            coupon(expiresAt = LocalDateTime.now().minusSeconds(1)).redeem()
        }.errorCode shouldBe ErrorCode.COUPON_EXPIRED
    }

    @Test
    fun `cancel transitions REDEEMED to CANCELLED`() {
        val c = coupon(status = CouponStatus.REDEEMED)
        c.cancel()
        c.status shouldBe CouponStatus.CANCELLED
    }

    @Test
    fun `cancel on a non-REDEEMED coupon throws INVALID_STATE_TRANSITION`() {
        shouldThrow<BusinessException> { coupon(status = CouponStatus.ISSUED).cancel() }
            .errorCode shouldBe ErrorCode.INVALID_STATE_TRANSITION
    }
}
