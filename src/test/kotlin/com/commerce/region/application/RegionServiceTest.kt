package com.commerce.region.application

import com.commerce.region.domain.RegionStatus
import com.commerce.region.interfaces.dto.CreateRegionRequest
import com.commerce.support.IntegrationTestSupport
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal

@Transactional
class RegionServiceTest : IntegrationTestSupport() {

    @Autowired
    lateinit var regionService: RegionService

    @Test
    fun `should create a region with policy`() {
        val request = CreateRegionRequest(
            name = "성남시",
            regionCode = "SN",
            discountRate = BigDecimal("0.10"),
            purchaseLimitPerPerson = BigDecimal("500000"),
            monthlyIssuanceLimit = BigDecimal("10000000000"),
        )

        val region = regionService.create(request)

        region.id shouldNotBe 0L
        region.name shouldBe "성남시"
        region.status shouldBe RegionStatus.ACTIVE
        region.policy.discountRate.compareTo(BigDecimal("0.10")) shouldBe 0
        region.policy.refundThresholdRatio.compareTo(BigDecimal("0.60")) shouldBe 0
    }

    @Test
    fun `should find region by id`() {
        val request = CreateRegionRequest(
            name = "부산시",
            regionCode = "BS",
            discountRate = BigDecimal("0.10"),
            purchaseLimitPerPerson = BigDecimal("500000"),
            monthlyIssuanceLimit = BigDecimal("5000000000"),
        )
        val created = regionService.create(request)

        val found = regionService.getById(created.id)

        found.name shouldBe "부산시"
        found.regionCode shouldBe "BS"
    }
}
