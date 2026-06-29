package com.commerce.promotion.interfaces

import com.commerce.common.idempotency.Idempotent
import com.commerce.common.security.SecurityUtils
import com.commerce.promotion.application.CouponIssueService
import com.commerce.promotion.application.PromotionService
import com.commerce.promotion.interfaces.dto.CouponResponse
import com.commerce.promotion.interfaces.dto.CreatePromotionRequest
import com.commerce.promotion.interfaces.dto.PromotionResponse
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/v1/promotions")
class PromotionController(
    private val promotionService: PromotionService,
    private val couponIssueService: CouponIssueService,
) {

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(@Valid @RequestBody request: CreatePromotionRequest): PromotionResponse =
        PromotionResponse.from(promotionService.create(request))

    @GetMapping("/{id}")
    fun getById(@PathVariable id: Long): PromotionResponse =
        PromotionResponse.from(promotionService.getById(id))

    @PostMapping("/{id}/coupons")
    @ResponseStatus(HttpStatus.CREATED)
    @Idempotent
    fun issueCoupon(@PathVariable id: Long): CouponResponse =
        CouponResponse.from(couponIssueService.issue(id, SecurityUtils.currentMemberId()))
}
