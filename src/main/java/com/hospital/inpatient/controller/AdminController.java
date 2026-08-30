package com.hospital.inpatient.controller;

import com.hospital.inpatient.common.ApiResponse;
import com.hospital.inpatient.common.PageResult;
import com.hospital.inpatient.mapper.AdminMapper;
import com.hospital.inpatient.mapper.AuditMapper;
import com.hospital.inpatient.service.AdminService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/admin")
@PreAuthorize("hasAuthority('ADMIN_MANAGE')")
public class AdminController {
    private final AdminMapper mapper;
    private final AuditMapper auditMapper;
    private final AdminService service;

    public AdminController(AdminMapper mapper, AuditMapper auditMapper, AdminService service) {
        this.mapper = mapper;
        this.auditMapper = auditMapper;
        this.service = service;
    }

    @GetMapping("/users")
    public ApiResponse<?> users() {
        return ApiResponse.success(mapper.users());
    }

    @PostMapping("/users")
    public ApiResponse<?> create(@Valid @RequestBody UserCreate request) {
        return ApiResponse.success("用户创建成功",
                service.createUser(request.staffId(), request.username(), request.displayName(),
                        request.password(), request.roles()));
    }

    @PutMapping("/users/{id}/enabled")
    public ApiResponse<?> enabled(@PathVariable long id, @Valid @RequestBody EnabledUpdate body) {
        service.setEnabled(id, body.enabled());
        return ApiResponse.success("账号状态已更新", null);
    }

    @GetMapping("/roles")
    public ApiResponse<?> roles() {
        return ApiResponse.success(mapper.roles());
    }

    @GetMapping("/permissions")
    public ApiResponse<?> permissions() {
        return ApiResponse.success(mapper.permissions());
    }

    @GetMapping("/audit-logs")
    public ApiResponse<?> audit(@RequestParam(required = false) String username,
                                @RequestParam(required = false) String action,
                                @RequestParam(defaultValue = "1") int page,
                                @RequestParam(defaultValue = "20") int size) {
        int safeSize = Math.min(Math.max(size, 1), 100);
        int safePage = Math.max(page, 1);
        return ApiResponse.success(new PageResult<>(
                auditMapper.list(username, action, (safePage - 1) * safeSize, safeSize),
                auditMapper.count(username, action), safePage, safeSize));
    }

    public record UserCreate(@NotNull Long staffId,
                             @NotBlank @Size(max = 64) String username,
                             @NotBlank @Size(max = 100) String displayName,
                             @NotBlank @Size(min = 10, max = 100) String password,
                             @NotEmpty List<String> roles) {
    }

    public record EnabledUpdate(@NotNull Boolean enabled) {}
}
