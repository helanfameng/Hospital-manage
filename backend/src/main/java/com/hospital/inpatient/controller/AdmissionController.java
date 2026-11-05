package com.hospital.inpatient.controller;

import com.hospital.inpatient.common.ApiResponse;
import com.hospital.inpatient.dto.Requests;
import com.hospital.inpatient.service.AdmissionService;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admissions")
public class AdmissionController {
    private final AdmissionService service;

    public AdmissionController(AdmissionService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('PATIENT_READ')")
    public ApiResponse<?> list(@RequestParam(required = false) String status,
                               @RequestParam(required = false) Long departmentId,
                               @RequestParam(required = false) String keyword,
                               @RequestParam(required = false) Integer longStayDays,
                               @RequestParam(required = false) Boolean settlementBlocked,
                               @RequestParam(defaultValue = "1") int page,
                               @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(service.list(status, departmentId, keyword, longStayDays, settlementBlocked, page, size));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('PATIENT_READ')")
    public ApiResponse<?> detail(@PathVariable long id) {
        return ApiResponse.success(service.detail(id));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('ADMISSION_MANAGE')")
    public ApiResponse<?> admit(@Valid @RequestBody Requests.AdmissionCreate request) {
        return ApiResponse.success("入院登记成功", service.admit(request));
    }

    @PostMapping("/{id}/transfer")
    @PreAuthorize("hasAuthority('ADMISSION_MANAGE')")
    public ApiResponse<?> transfer(@PathVariable long id, @Valid @RequestBody Requests.TransferCreate request) {
        return ApiResponse.success("转床成功", service.transfer(id, request));
    }

    @PostMapping("/{id}/request-discharge")
    @PreAuthorize("hasAuthority('ADMISSION_MANAGE')")
    public ApiResponse<?> requestDischarge(@PathVariable long id) {
        service.requestDischarge(id);
        return ApiResponse.success("已进入待出院状态", null);
    }

    @PostMapping("/{id}/withdraw-discharge")
    @PreAuthorize("hasAuthority('ADMISSION_MANAGE')")
    public ApiResponse<?> withdraw(@PathVariable long id) {
        service.withdrawDischarge(id);
        return ApiResponse.success("出院申请已撤回", null);
    }
}
