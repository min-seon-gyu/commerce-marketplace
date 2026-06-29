package com.commerce.merchant.interfaces

import com.commerce.merchant.application.SettlementService
import com.commerce.merchant.domain.Settlement
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.*
import java.math.BigDecimal
import java.time.LocalDate

data class SettlementResponse(
    val id: Long,
    val merchantId: Long,
    val periodStart: LocalDate,
    val periodEnd: LocalDate,
    val totalAmount: BigDecimal,
    val status: String,
    val disputeReason: String?,
) {
    companion object {
        fun from(s: Settlement) = SettlementResponse(
            id = s.id,
            merchantId = s.merchantId,
            periodStart = s.periodStart,
            periodEnd = s.periodEnd,
            totalAmount = s.totalAmount,
            status = s.status.name,
            disputeReason = s.disputeReason,
        )
    }
}

data class CalculateSettlementRequest(
    val merchantId: Long,
    @field:DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    val periodStart: LocalDate,
    @field:DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    val periodEnd: LocalDate,
)

data class DisputeRequest(
    val reason: String,
)

@RestController
@RequestMapping("/api/v1/settlements")
class SettlementController(
    private val settlementService: SettlementService,
) {

    @PostMapping("/calculate")
    @ResponseStatus(HttpStatus.CREATED)
    fun calculate(@RequestBody request: CalculateSettlementRequest): SettlementResponse =
        SettlementResponse.from(
            settlementService.calculate(request.merchantId, request.periodStart, request.periodEnd)
        )

    @PostMapping("/{id}/confirm")
    fun confirm(@PathVariable id: Long): SettlementResponse =
        SettlementResponse.from(settlementService.confirm(id))

    @PostMapping("/{id}/dispute")
    fun dispute(@PathVariable id: Long, @RequestBody request: DisputeRequest): SettlementResponse =
        SettlementResponse.from(settlementService.dispute(id, request.reason))

    @GetMapping("/{id}")
    fun getById(@PathVariable id: Long): SettlementResponse =
        SettlementResponse.from(settlementService.getById(id))
}
