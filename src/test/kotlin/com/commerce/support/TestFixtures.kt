package com.commerce.support

import com.commerce.member.application.MemberService
import com.commerce.member.domain.Member
import com.commerce.member.interfaces.dto.RegisterMemberRequest
import com.commerce.merchant.application.MerchantService
import com.commerce.merchant.application.RegisterMerchantRequest
import com.commerce.merchant.domain.Merchant
import com.commerce.region.application.RegionService
import com.commerce.region.domain.Region
import com.commerce.region.interfaces.dto.CreateRegionRequest
import com.commerce.voucher.application.VoucherIssueService
import com.commerce.voucher.domain.Voucher
import com.commerce.voucher.infrastructure.VoucherJpaRepository
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.LocalDateTime

@Component
class TestFixtures(
    private val regionService: RegionService,
    private val memberService: MemberService,
    private val merchantService: MerchantService,
    private val voucherIssueService: VoucherIssueService,
    private val voucherJpaRepository: VoucherJpaRepository,
) {
    private var counter = 0
    private val base36 = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ"

    /** 충돌 없는 유니크 2글자 region 코드 생성 (region_code 컬럼이 length=2, unique 제약) */
    private fun nextRegionCode(): String {
        val n = (counter++) % (36 * 36)
        return "${base36[n / 36]}${base36[n % 36]}"
    }

    fun createRegion(
        name: String = "성남시",
        code: String = "SN", // (미사용) 코드는 nextRegionCode()로 유니크 생성해 충돌 방지
        monthlyLimit: BigDecimal = BigDecimal("10000000000"),
        purchaseLimit: BigDecimal = BigDecimal("5000000"),
    ): Region {
        return regionService.create(
            CreateRegionRequest(
                name = name,
                regionCode = nextRegionCode(),
                discountRate = BigDecimal("0.10"),
                purchaseLimitPerPerson = purchaseLimit,
                monthlyIssuanceLimit = monthlyLimit,
            )
        )
    }

    fun createMember(email: String? = null): Member {
        counter++
        return memberService.register(
            RegisterMemberRequest(
                email = email ?: "user$counter@test.com",
                name = "테스트유저$counter",
                password = "password123",
            )
        )
    }

    fun createMerchant(region: Region, owner: Member): Merchant {
        val merchant = merchantService.register(
            RegisterMerchantRequest(
                name = "테스트가게${counter++}",
                businessNumber = "123-45-${String.format("%05d", counter)}",
                category = "RESTAURANT",
                regionId = region.id,
                ownerId = owner.id,
            )
        )
        return merchantService.approve(merchant.id)
    }

    fun issueVoucher(
        memberId: Long,
        regionId: Long,
        faceValue: BigDecimal = BigDecimal("50000"),
    ): Voucher {
        return voucherIssueService.issue(memberId, regionId, faceValue)
    }

    @Transactional
    fun forceExpireVoucher(voucherId: Long) {
        voucherJpaRepository.updateExpiresAt(voucherId, LocalDateTime.now().minusDays(1))
    }

    @Transactional
    fun forcePurchasedAt(voucherId: Long, purchasedAt: LocalDateTime) {
        voucherJpaRepository.updatePurchasedAt(voucherId, purchasedAt)
    }
}
