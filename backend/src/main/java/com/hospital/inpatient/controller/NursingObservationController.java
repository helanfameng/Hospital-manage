package com.hospital.inpatient.controller;

import com.hospital.inpatient.common.ApiResponse;
import com.hospital.inpatient.dto.Requests;
import com.hospital.inpatient.service.NursingObservationService;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admissions/{admissionId}/observations")
public class NursingObservationController {
    private final NursingObservationService service;

    public NursingObservationController(NursingObservationService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('PATIENT_READ')")
    public ApiResponse<?> list(@PathVariable long admissionId) {
        return ApiResponse.success(service.list(admissionId));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('NURSING_RECORD')")
    public ApiResponse<?> create(@PathVariable long admissionId,
                                 @Valid @RequestBody Requests.NursingObservationCreate request) {
        return ApiResponse.success("护理观察记录成功", service.create(admissionId, request));
    }
}
