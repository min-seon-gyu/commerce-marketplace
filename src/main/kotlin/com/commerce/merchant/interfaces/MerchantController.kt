package com.commerce.merchant.interfaces

import com.commerce.merchant.application.MerchantService
import com.commerce.merchant.application.RegisterMerchantRequest
import com.commerce.merchant.domain.Merchant
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.*

data class MerchantResponse(
    val id: Long,
    val name: String,
    val businessNumber: String,
    val category: String,
    val regionId: Long,
    val status: String,
) {
    companion object {
        fun from(m: Merchant) = MerchantResponse(
            id = m.id,
            name = m.name,
            businessNumber = m.businessNumber,
            category = m.category.name,
            regionId = m.region.id,
            status = m.status.name,
        )
    }
}

@RestController
@RequestMapping("/api/v1/merchants")
class MerchantController(
    private val merchantService: MerchantService,
) {

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun register(@RequestBody request: RegisterMerchantRequest) =
        MerchantResponse.from(merchantService.register(request))

    @PostMapping("/{id}/approve")
    fun approve(@PathVariable id: Long) = MerchantResponse.from(merchantService.approve(id))

    @PostMapping("/{id}/reject")
    fun reject(@PathVariable id: Long) = MerchantResponse.from(merchantService.reject(id))

    @PostMapping("/{id}/suspend")
    fun suspend(@PathVariable id: Long) = MerchantResponse.from(merchantService.suspend(id))

    @PostMapping("/{id}/unsuspend")
    fun unsuspend(@PathVariable id: Long) = MerchantResponse.from(merchantService.unsuspend(id))

    @PostMapping("/{id}/terminate")
    fun terminate(@PathVariable id: Long) = MerchantResponse.from(merchantService.terminate(id))

    @GetMapping("/{id}")
    fun getById(@PathVariable id: Long) = MerchantResponse.from(merchantService.getById(id))
}
