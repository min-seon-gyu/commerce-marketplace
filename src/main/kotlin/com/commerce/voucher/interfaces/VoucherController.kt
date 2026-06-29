package com.commerce.voucher.interfaces

import com.commerce.common.exception.BusinessException
import com.commerce.common.exception.ErrorCode
import com.commerce.common.idempotency.Idempotent
import com.commerce.voucher.application.VoucherIssueService
import com.commerce.voucher.application.VoucherRedemptionService
import com.commerce.voucher.application.VoucherRefundService
import com.commerce.voucher.application.VoucherWithdrawalService
import com.commerce.voucher.domain.VoucherStatus
import com.commerce.voucher.infrastructure.VoucherJpaRepository
import com.commerce.voucher.infrastructure.VoucherQueryRepository
import com.commerce.voucher.interfaces.dto.*
import jakarta.validation.Valid
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.web.PageableDefault
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/v1/vouchers")
class VoucherController(
    private val issueService: VoucherIssueService,
    private val redemptionService: VoucherRedemptionService,
    private val refundService: VoucherRefundService,
    private val withdrawalService: VoucherWithdrawalService,
    private val voucherQueryRepository: VoucherQueryRepository,
    private val voucherJpaRepository: VoucherJpaRepository,
) {

    @GetMapping
    fun search(
        @RequestParam(required = false) memberId: Long?,
        @RequestParam(required = false) regionId: Long?,
        @RequestParam(required = false) status: VoucherStatus?,
        @PageableDefault(size = 20) pageable: Pageable,
    ): Page<VoucherResponse> =
        voucherQueryRepository.findByConditions(memberId, regionId, status, pageable)
            .map { VoucherResponse.from(it) }

    @GetMapping("/{id}")
    fun getById(@PathVariable id: Long): VoucherResponse =
        VoucherResponse.from(
            voucherJpaRepository.findById(id)
                .orElseThrow { BusinessException(ErrorCode.ENTITY_NOT_FOUND) }
        )

    @PostMapping("/purchase")
    @ResponseStatus(HttpStatus.CREATED)
    @Idempotent
    fun purchase(@Valid @RequestBody request: PurchaseVoucherRequest): VoucherResponse =
        VoucherResponse.from(issueService.issue(request.memberId, request.regionId, request.faceValue))

    @PostMapping("/{id}/redeem")
    @Idempotent
    fun redeem(@PathVariable id: Long, @Valid @RequestBody request: RedeemRequest): RedemptionResult =
        redemptionService.redeem(id, request.merchantId, request.amount)

    @PostMapping("/{id}/refund")
    @Idempotent
    fun refund(@PathVariable id: Long, @RequestBody request: RefundRequest): VoucherResponse =
        VoucherResponse.from(refundService.refund(id, request.memberId))

    @PostMapping("/{id}/withdraw")
    @Idempotent
    fun withdraw(@PathVariable id: Long, @RequestBody request: WithdrawRequest): VoucherResponse =
        VoucherResponse.from(withdrawalService.withdraw(id, request.memberId))
}
