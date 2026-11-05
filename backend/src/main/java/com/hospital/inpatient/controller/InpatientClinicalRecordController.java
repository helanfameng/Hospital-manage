package com.hospital.inpatient.controller;

import com.hospital.inpatient.common.ApiResponse;
import com.hospital.inpatient.dto.Requests;
import com.hospital.inpatient.service.InpatientClinicalRecordService;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class InpatientClinicalRecordController {
    private final InpatientClinicalRecordService service;

    public InpatientClinicalRecordController(InpatientClinicalRecordService service) {
        this.service = service;
    }

    @GetMapping("/diagnosis-catalog")
    @PreAuthorize("hasAuthority('PATIENT_READ')")
    public ApiResponse<?> catalog() {
        return ApiResponse.success(service.listCatalog());
    }

    @GetMapping("/admissions/{admissionId}/diagnoses")
    @PreAuthorize("hasAuthority('PATIENT_READ')")
    public ApiResponse<?> diagnoses(@PathVariable long admissionId) {
        return ApiResponse.success(service.listDiagnoses(admissionId));
    }

    @PostMapping("/admissions/{admissionId}/diagnoses")
    @PreAuthorize("hasAuthority('DIAGNOSIS_RECORD')")
    public ApiResponse<?> createDiagnosis(@PathVariable long admissionId,
                                          @Valid @RequestBody Requests.InpatientDiagnosisCreate request) {
        return ApiResponse.success("住院诊断记录成功", service.createDiagnosis(admissionId, request));
    }

    @PostMapping("/admissions/{admissionId}/diagnoses/{diagnosisId}/resolve")
    @PreAuthorize("hasAuthority('DIAGNOSIS_RECORD')")
    public ApiResponse<?> resolveDiagnosis(@PathVariable long admissionId, @PathVariable long diagnosisId,
                                           @Valid @RequestBody Requests.InpatientDiagnosisResolve request) {
        return ApiResponse.success("住院诊断状态已更新", service.resolveDiagnosis(admissionId, diagnosisId, request));
    }

    @GetMapping("/admissions/{admissionId}/clinical-notes")
    @PreAuthorize("hasAuthority('PATIENT_READ')")
    public ApiResponse<?> notes(@PathVariable long admissionId) {
        return ApiResponse.success(service.listNotes(admissionId));
    }

    @PostMapping("/admissions/{admissionId}/clinical-notes")
    @PreAuthorize("hasAuthority('CLINICAL_RECORD')")
    public ApiResponse<?> createNote(@PathVariable long admissionId,
                                     @Valid @RequestBody Requests.InpatientClinicalNoteCreate request) {
        return ApiResponse.success("住院临床记录签署成功", service.createNote(admissionId, request));
    }
}
