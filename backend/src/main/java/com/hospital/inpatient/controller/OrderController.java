package com.hospital.inpatient.controller;

import com.hospital.inpatient.common.ApiResponse;
import com.hospital.inpatient.dto.Requests;
import com.hospital.inpatient.service.OrderService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@Validated
@RequestMapping("/api")
public class OrderController {
    private final OrderService service;

    public OrderController(OrderService service) {
        this.service = service;
    }

    @GetMapping("/orders")
    @PreAuthorize("hasAuthority('PATIENT_READ')")
    public ApiResponse<?> orders(@RequestParam(required = false) Long admissionId,
                                 @RequestParam(required = false) String status,
                                 @RequestParam(required = false) @jakarta.validation.constraints.Size(max=120) String patientName,
                                 @RequestParam(required = false) @jakarta.validation.constraints.Size(max=32) String orderNo,
                                 @RequestParam(defaultValue = "1") int page,
                                 @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(service.list(admissionId, status, patientName, orderNo, page, size));
    }

    @GetMapping("/orders/{id}")
    @PreAuthorize("hasAuthority('PATIENT_READ')")
    public ApiResponse<?> detail(@PathVariable long id) {
        return ApiResponse.success(service.detail(id));
    }

    @PostMapping("/orders")
    @PreAuthorize("hasAuthority('ORDER_WRITE')")
    public ApiResponse<?> create(@Valid @RequestBody Requests.OrderCreate request) {
        return ApiResponse.success("医嘱草稿已创建", service.create(request));
    }

    @PostMapping("/orders/{id}/{action:submit|stop|cancel|complete}")
    @PreAuthorize("hasAuthority('ORDER_WRITE')")
    public ApiResponse<?> status(@PathVariable long id, @PathVariable String action) {
        return ApiResponse.success("医嘱状态已更新", service.changeStatus(id, action));
    }

}
