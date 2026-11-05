package com.hospital.inpatient.controller;

import com.hospital.inpatient.common.ApiResponse;
import com.hospital.inpatient.dto.Requests;
import com.hospital.inpatient.service.TypedOrderService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/api")
public class TypedOrderController {
    private final TypedOrderService service;
    public TypedOrderController(TypedOrderService service) { this.service=service; }
    @GetMapping("/order-catalog") @PreAuthorize("hasAuthority('PATIENT_READ')")
    public ApiResponse<?> catalog() { return ApiResponse.success(service.catalog()); }
    @PostMapping("/order-catalog") @PreAuthorize("hasAuthority('ADMIN_MANAGE')")
    public ApiResponse<?> saveCatalog(@Valid @RequestBody Catalog body) { return ApiResponse.success(service.saveCatalog(body)); }
    @GetMapping("/order-templates") @PreAuthorize("hasAuthority('ORDER_WRITE')")
    public ApiResponse<?> templates() { return ApiResponse.success(service.templates()); }
    @PostMapping("/order-templates") @PreAuthorize("hasAuthority('ORDER_WRITE')")
    public ApiResponse<?> template(@Valid @RequestBody Template body) { return ApiResponse.success(service.saveTemplate(body)); }
    @PostMapping("/order-templates/{id}/disable") @PreAuthorize("hasAuthority('ORDER_WRITE')")
    public ApiResponse<?> disable(@PathVariable long id) { service.disableTemplate(id); return ApiResponse.success("模板已停用",null); }
    @GetMapping("/orders/tasks") @PreAuthorize("hasAnyAuthority('ORDER_EXECUTE','MEDICATION_ADMINISTER','ORDER_WRITE')")
    public ApiResponse<?> tasks(@RequestParam String phase,@RequestParam(required=false) String itemType,
            @RequestParam(defaultValue="EXECUTABLE") String view,@RequestParam(required=false) Integer windowHours,
            @RequestParam(required=false) String keyword,@RequestParam(required=false) String administrationMode,
            @RequestParam(required=false) String timeliness,@RequestParam(required=false) Instant scheduledFrom,
            @RequestParam(required=false) Instant scheduledTo,@RequestParam(required=false) String dispenseStatus,
            @RequestParam(required=false) Long departmentId,@RequestParam(defaultValue="PLANNED_AT_ASC") String sort,
            @RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int size) {
        return ApiResponse.success(service.tasks(phase,itemType,view,windowHours,keyword,administrationMode,timeliness,
                scheduledFrom,scheduledTo,dispenseStatus,departmentId,sort,page,size));
    }
    @PostMapping("/orders/executions") @PreAuthorize("hasAnyAuthority('ORDER_EXECUTE','MEDICATION_ADMINISTER','ORDER_WRITE')")
    public ApiResponse<?> execute(@Valid @RequestBody Requests.OrderExecution body) { return ApiResponse.success(service.execute(body)); }
    @PostMapping("/orders/executions/{id}/reverse") @PreAuthorize("hasAnyAuthority('ORDER_EXECUTE','MEDICATION_ADMINISTER','ORDER_WRITE')")
    public ApiResponse<?> reverse(@PathVariable long id,@Valid @RequestBody Requests.CorrectionCreate body) { service.reverse(id,body.reason()); return ApiResponse.success("执行已冲正",null); }
    @GetMapping("/orders/executions/history") @PreAuthorize("hasAuthority('PATIENT_READ')")
    public ApiResponse<?> history(@RequestParam long admissionId) { return ApiResponse.success(service.history(admissionId)); }
    @PostMapping("/orders/{id}/items/{itemId}/{action:submit|stop|cancel|complete}") @PreAuthorize("hasAuthority('ORDER_WRITE')")
    public ApiResponse<?> change(@PathVariable long id,@PathVariable long itemId,@PathVariable String action) { service.change(id,action,itemId); return ApiResponse.success("医嘱项目状态已更新",null); }

    public record Template(@NotBlank @Size(max=100) String name,@Size(max=500) String description,
            @NotEmpty List<@NotNull Long> medicalItemIds) {}
    public record Catalog(Long id,@NotBlank @Size(max=32) String code,@NotBlank @Size(max=120) String name,
            @NotBlank @Pattern(regexp="DRUG|EXAMINATION|LAB|TREATMENT|NURSING|DIET|OTHER") String category,
            @NotBlank @Size(max=20) String unit,@NotNull @DecimalMin("0") @Digits(integer=10,fraction=2) BigDecimal price,
            boolean billable,boolean allowFractionalQuantity,
            @Pattern(regexp="NURSE|DOCTOR") String executionRole,
            @Size(max=120) String specification,@Size(max=64) String dosageForm,
            @Size(max=20) String doseUnit,@DecimalMin("0.000001") @Digits(integer=12,fraction=6) BigDecimal doseUnitsPerStock,
            @Size(max=255) String allowedRoutes) {}
}
