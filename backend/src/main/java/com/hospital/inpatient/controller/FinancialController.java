package com.hospital.inpatient.controller;

import com.hospital.inpatient.common.ApiResponse;
import com.hospital.inpatient.dto.Requests;
import com.hospital.inpatient.mapper.FinancialMapper;
import com.hospital.inpatient.service.FinancialService;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class FinancialController {
    private final FinancialMapper mapper;
    private final FinancialService service;
    private final com.hospital.inpatient.service.AccountGuard guard;

    public FinancialController(FinancialMapper mapper, FinancialService service, com.hospital.inpatient.service.AccountGuard guard) {
        this.mapper = mapper;
        this.service = service;
        this.guard = guard;
    }

    @GetMapping("/deposits")
    @PreAuthorize("hasAuthority('PATIENT_READ')")
    public ApiResponse<?> deposits(@RequestParam long admissionId) {
        guard.requireRead(admissionId);
        return ApiResponse.success(mapper.deposits(admissionId));
    }

    @PostMapping("/deposits")
    @PreAuthorize("hasAuthority('DEPOSIT_MANAGE')")
    public ApiResponse<?> deposit(@Valid @RequestBody Requests.DepositCreate request) {
        return ApiResponse.success("押金流水已登记", service.addDeposit(request));
    }

    @PostMapping("/deposits/{id}/reverse")
    @PreAuthorize("hasAuthority('DEPOSIT_MANAGE')")
    public ApiResponse<?> reverseDeposit(@PathVariable long id,
                                         @Valid @RequestBody Requests.CorrectionCreate request) {
        return ApiResponse.success("押金流水已冲正", service.reverseDeposit(id, request.reason()));
    }

    @GetMapping("/charges")
    @PreAuthorize("hasAuthority('PATIENT_READ')")
    public ApiResponse<?> charges(@RequestParam long admissionId) {
        guard.requireRead(admissionId);
        return ApiResponse.success(mapper.charges(admissionId));
    }

    @GetMapping("/settlements/{admissionId}/precheck")
    @PreAuthorize("hasAuthority('SETTLEMENT_MANAGE')")
    public ApiResponse<?> precheck(@PathVariable long admissionId) {
        return ApiResponse.success(service.precheck(admissionId));
    }

    @PostMapping("/settlements/{admissionId}")
    @PreAuthorize("hasAuthority('SETTLEMENT_MANAGE')")
    public ApiResponse<?> settle(@PathVariable long admissionId) {
        return ApiResponse.success("出院结算完成，床位已释放", service.settle(admissionId));
    }

    @GetMapping("/settlements/{admissionId}")
    @PreAuthorize("hasAuthority('PATIENT_READ')")
    public ApiResponse<?> settlement(@PathVariable long admissionId) {
        return ApiResponse.success(service.settlement(admissionId));
    }

    @GetMapping("/alerts")
    @PreAuthorize("hasAuthority('DEPOSIT_MANAGE') or hasAuthority('SETTLEMENT_MANAGE')")
    public ApiResponse<?> alerts(@RequestParam(defaultValue = "OPEN") String status,
                                 @RequestParam(required = false) String level,
                                 @RequestParam(defaultValue = "1") int page,
                                 @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(service.alerts(status, level, page, size));
    }

    @PostMapping("/alerts/{id}/resolve")
    @PreAuthorize("hasAuthority('DEPOSIT_MANAGE')")
    public ApiResponse<?> resolve(@PathVariable long id) {
        service.resolveAlert(id);
        return ApiResponse.success("欠费提醒已处理", null);
    }
}
