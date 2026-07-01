package com.commerce.inventory.interfaces

import com.commerce.common.api.ApiResponse
import com.commerce.inventory.application.StockService
import org.springframework.web.bind.annotation.*

data class AdjustStockRequest(val quantity: Int)

data class StockResponse(val skuId: Long, val quantity: Int)

/**
 * 재고 운영(관리자). 경로가 `/api/v1/admin` 하위이므로 SecurityConfig에서 ADMIN 게이트된다.
 * 입고/정정은 adjust(실수량 세팅)로 처리한다. 주문 차감/복원은 주문 도메인이 StockService를 통해 수행한다(Phase 3).
 */
@RestController
@RequestMapping("/api/v1/admin/inventory")
class InventoryController(
    private val stockService: StockService,
) {

    @PutMapping("/skus/{skuId}")
    fun adjust(@PathVariable skuId: Long, @RequestBody request: AdjustStockRequest): ApiResponse<StockResponse> {
        val quantity = stockService.adjust(skuId, request.quantity)
        return ApiResponse.ok(StockResponse(skuId, quantity))
    }

    @GetMapping("/skus/{skuId}")
    fun get(@PathVariable skuId: Long): ApiResponse<StockResponse> {
        val stock = stockService.getBySkuId(skuId)
        return ApiResponse.ok(StockResponse(stock.skuId, stock.quantity))
    }
}
