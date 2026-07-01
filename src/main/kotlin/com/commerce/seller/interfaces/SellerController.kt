package com.commerce.seller.interfaces

import com.commerce.common.api.ApiResponse
import com.commerce.common.security.SecurityUtils
import com.commerce.seller.application.SellerService
import com.commerce.seller.application.RegisterSellerRequest
import com.commerce.seller.domain.Seller
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.*

data class SellerResponse(
    val id: Long,
    val name: String,
    val businessNumber: String,
    val category: String,
    val settlementPeriod: String,
    val status: String,
) {
    companion object {
        fun from(m: Seller) = SellerResponse(
            id = m.id,
            name = m.name,
            businessNumber = m.businessNumber,
            category = m.category.name,
            settlementPeriod = m.settlementPeriod.name,
            status = m.status.name,
        )
    }
}

@RestController
@RequestMapping("/api/v1/sellers")
class SellerController(
    private val sellerService: SellerService,
) {

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun register(@RequestBody request: RegisterSellerRequest): ApiResponse<SellerResponse> =
        // 소유자는 인증 주체로 강제(본문 ownerId 불신) — 타인 명의 등록·임의 권한 상승 차단.
        ApiResponse.ok(SellerResponse.from(
            sellerService.register(request.copy(ownerId = SecurityUtils.currentMemberId()))
        ))

    @PostMapping("/{id}/approve")
    fun approve(@PathVariable id: Long): ApiResponse<SellerResponse> =
        ApiResponse.ok(SellerResponse.from(sellerService.approve(id)))

    @PostMapping("/{id}/reject")
    fun reject(@PathVariable id: Long): ApiResponse<SellerResponse> =
        ApiResponse.ok(SellerResponse.from(sellerService.reject(id)))

    @PostMapping("/{id}/suspend")
    fun suspend(@PathVariable id: Long): ApiResponse<SellerResponse> =
        ApiResponse.ok(SellerResponse.from(sellerService.suspend(id)))

    @PostMapping("/{id}/unsuspend")
    fun unsuspend(@PathVariable id: Long): ApiResponse<SellerResponse> =
        ApiResponse.ok(SellerResponse.from(sellerService.unsuspend(id)))

    @PostMapping("/{id}/terminate")
    fun terminate(@PathVariable id: Long): ApiResponse<SellerResponse> =
        ApiResponse.ok(SellerResponse.from(sellerService.terminate(id)))

    @GetMapping("/{id}")
    fun getById(@PathVariable id: Long): ApiResponse<SellerResponse> =
        ApiResponse.ok(SellerResponse.from(sellerService.getById(id)))
}
