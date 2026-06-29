package com.commerce.region.interfaces

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
    fun create(@Valid @RequestBody request: CreateRegionRequest): RegionResponse =
        RegionResponse.from(regionService.create(request))

    @GetMapping("/{id}")
    fun getById(@PathVariable id: Long): RegionResponse =
        RegionResponse.from(regionService.getById(id))

    @PutMapping("/{id}/policy")
    fun updatePolicy(@PathVariable id: Long, @Valid @RequestBody request: CreateRegionRequest): RegionResponse =
        RegionResponse.from(regionService.updatePolicy(id, request))

    @PostMapping("/{id}/suspend")
    fun suspend(@PathVariable id: Long): RegionResponse =
        RegionResponse.from(regionService.suspend(id))

    @PostMapping("/{id}/activate")
    fun activate(@PathVariable id: Long): RegionResponse =
        RegionResponse.from(regionService.activate(id))

    @GetMapping
    fun findAll(): List<RegionResponse> =
        regionService.findAll().map { RegionResponse.from(it) }
}
