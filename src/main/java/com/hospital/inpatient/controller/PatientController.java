package com.hospital.inpatient.controller;

import com.hospital.inpatient.common.ApiResponse;
import com.hospital.inpatient.dto.Requests;
import com.hospital.inpatient.service.PatientService;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/patients")
public class PatientController {
    private final PatientService service;

    public PatientController(PatientService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('PATIENT_READ')")
    public ApiResponse<?> list(@RequestParam(required = false) String keyword,
                               @RequestParam(defaultValue = "1") int page,
                               @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(service.list(keyword, page, size));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('PATIENT_READ')")
    public ApiResponse<?> detail(@PathVariable long id) {
        return ApiResponse.success(service.detail(id));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('PATIENT_WRITE')")
    public ApiResponse<?> create(@Valid @RequestBody Requests.PatientCreate request) {
        return ApiResponse.success("患者创建成功", service.create(request));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('PATIENT_WRITE')")
    public ApiResponse<?> update(@PathVariable long id, @Valid @RequestBody Requests.PatientUpdate request) {
        return ApiResponse.success("患者资料已更新", service.update(id, request));
    }
}

