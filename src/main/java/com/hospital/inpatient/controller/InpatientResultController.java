package com.hospital.inpatient.controller;

import com.hospital.inpatient.common.ApiResponse;
import com.hospital.inpatient.dto.Requests;
import com.hospital.inpatient.service.InpatientResultService;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admissions/{admissionId}/results")
public class InpatientResultController {
    private final InpatientResultService service;

    public InpatientResultController(InpatientResultService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('PATIENT_READ')")
    public ApiResponse<?> list(@PathVariable long admissionId) {
        return ApiResponse.success(service.list(admissionId));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('RESULT_RECORD')")
    public ApiResponse<?> create(@PathVariable long admissionId,
                                 @Valid @RequestBody Requests.InpatientResultCreate request) {
        return ApiResponse.success("住院检查检验结果记录成功", service.create(admissionId, request));
    }
}
