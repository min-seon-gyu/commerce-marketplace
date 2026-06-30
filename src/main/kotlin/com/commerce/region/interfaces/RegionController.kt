package com.commerce.region.interfaces

import com.commerce.common.api.ApiResponse
import com.commerce.region.application.RegionService
import com.commerce.region.interfaces.dto.CreateRegionRequest
import com.commerce.region.interfaces.dto.RegionResponse
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/v1/regions")
class RegionController(
    private val regionService: RegionService
) {

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(@Valid @RequestBody request: CreateRegionRequest): ApiResponse<RegionResponse> =
        ApiResponse.ok(RegionResponse.from(regionService.create(request)))

    @GetMapping("/{id}")
    fun getById(@PathVariable id: Long): ApiResponse<RegionResponse> =
        ApiResponse.ok(RegionResponse.from(regionService.getById(id)))

    @PutMapping("/{id}/policy")
    fun updatePolicy(@PathVariable id: Long, @Valid @RequestBody request: CreateRegionRequest): ApiResponse<RegionResponse> =
        ApiResponse.ok(RegionResponse.from(regionService.updatePolicy(id, request)))

    @PostMapping("/{id}/suspend")
    fun suspend(@PathVariable id: Long): ApiResponse<RegionResponse> =
        ApiResponse.ok(RegionResponse.from(regionService.suspend(id)))

    @PostMapping("/{id}/activate")
    fun activate(@PathVariable id: Long): ApiResponse<RegionResponse> =
        ApiResponse.ok(RegionResponse.from(regionService.activate(id)))

    @GetMapping
    fun findAll(): ApiResponse<List<RegionResponse>> =
        ApiResponse.ok(regionService.findAll().map { RegionResponse.from(it) })
}
