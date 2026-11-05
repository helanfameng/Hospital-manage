package com.hospital.inpatient.controller;

import com.hospital.inpatient.common.ApiResponse;
import com.hospital.inpatient.service.InventoryService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;

@RestController
@RequestMapping("/api/inventory")
@PreAuthorize("hasAnyAuthority('PHARMACY_EXECUTE','ADMIN_MANAGE')")
public class InventoryController {
    private final InventoryService service;

    public InventoryController(InventoryService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<?> items(@RequestParam(required = false) String keyword) {
        return ApiResponse.success(service.items(keyword));
    }

    @GetMapping("/{itemId}/transactions")
    public ApiResponse<?> transactions(@PathVariable long itemId,
                                       @RequestParam(defaultValue = "1") int page,
                                       @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(service.transactions(itemId, page, size));
    }

    @PostMapping("/{itemId}/inbound")
    public ApiResponse<?> inbound(@PathVariable long itemId, @Valid @RequestBody Inbound body) {
        return ApiResponse.success(service.inbound(itemId, body.quantity(), body.note()));
    }

    @PostMapping("/{itemId}/adjustment")
    public ApiResponse<?> adjustment(@PathVariable long itemId, @Valid @RequestBody Adjustment body) {
        return ApiResponse.success(service.adjust(itemId, body.actualQuantity(), body.note()));
    }

    @PutMapping("/{itemId}/threshold")
    public ApiResponse<?> threshold(@PathVariable long itemId, @Valid @RequestBody Threshold body) {
        return ApiResponse.success(service.setThreshold(itemId, body.threshold()));
    }

    public record Inbound(
            @NotNull @DecimalMin("0.001") @Digits(integer = 9, fraction = 3) BigDecimal quantity,
            @NotBlank @Size(max = 255) String note) {}

    public record Adjustment(
            @NotNull @DecimalMin("0") @Digits(integer = 9, fraction = 3) BigDecimal actualQuantity,
            @NotBlank @Size(max = 255) String note) {}

    public record Threshold(
            @DecimalMin("0") @Digits(integer = 9, fraction = 3) BigDecimal threshold) {}
}
