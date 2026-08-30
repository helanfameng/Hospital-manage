package com.hospital.inpatient.controller;

import com.hospital.inpatient.common.ApiResponse;
import com.hospital.inpatient.dto.Requests;
import com.hospital.inpatient.exception.BusinessException;
import com.hospital.inpatient.mapper.HospitalMapper;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class BedController {
    private final HospitalMapper mapper;
    private final com.hospital.inpatient.service.AuditService audit;

    public BedController(HospitalMapper mapper, com.hospital.inpatient.service.AuditService audit) {
        this.mapper = mapper;
        this.audit = audit;
    }

    @GetMapping("/beds")
    @PreAuthorize("hasAuthority('PATIENT_READ')")
    public ApiResponse<?> beds(@RequestParam(required = false) Long wardId,
                               @RequestParam(required = false) String status) {
        return ApiResponse.success(mapper.listBeds(wardId, status));
    }

    @PutMapping("/beds/{id}/status")
    @org.springframework.transaction.annotation.Transactional
    @PreAuthorize("hasAuthority('ADMISSION_MANAGE') or hasAuthority('ADMIN_MANAGE')")
    public ApiResponse<?> bedStatus(@PathVariable long id, @Valid @RequestBody Requests.BedStatusUpdate request) {
        if (mapper.updateBedStatus(id, request.status()) != 1) {
            throw new BusinessException("BED_STATUS_CONFLICT", "占用中的床位不能改为维修或停用");
        }
        audit.success("BED_STATUS","bed",id,null,java.util.Map.of("status",request.status()));
        return ApiResponse.success("床位状态已更新", null);
    }

    @GetMapping("/admin/departments")
    public ApiResponse<?> departments() {
        return ApiResponse.success(mapper.listDepartments());
    }

    @GetMapping("/admin/staff")
    public ApiResponse<?> staff(@RequestParam(required = false) String staffType,
                                @RequestParam(required = false) Long departmentId) {
        return ApiResponse.success(mapper.listStaff(staffType, departmentId));
    }

    @GetMapping("/admin/medical-items")
    public ApiResponse<?> items(@RequestParam(required = false) String itemType,
                                @RequestParam(required = false) String keyword) {
        return ApiResponse.success(mapper.listMedicalItems(itemType, keyword));
    }
}
