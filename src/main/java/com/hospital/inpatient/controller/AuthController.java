package com.hospital.inpatient.controller;

import com.hospital.inpatient.common.ApiResponse;
import com.hospital.inpatient.security.SecurityUtils;
import com.hospital.inpatient.service.AuthService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final AuthService authService;
    private final com.hospital.inpatient.service.AdminService accountService;

    public AuthController(AuthService authService, com.hospital.inpatient.service.AdminService accountService) {
        this.authService = authService;
        this.accountService = accountService;
    }

    @PostMapping("/login")
    public ApiResponse<Map<String, Object>> login(@Valid @RequestBody LoginRequest request) {
        return ApiResponse.success("登录成功", authService.login(request.username(), request.password()));
    }

    @GetMapping("/me")
    public ApiResponse<?> me() {
        return ApiResponse.success(authService.me(SecurityUtils.currentUser().username()));
    }

    @PostMapping("/password")
    public ApiResponse<?> password(@Valid @RequestBody PasswordChange request) {
        accountService.changePassword(request.oldPassword(),request.newPassword());
        return ApiResponse.success("密码已修改，请重新登录",null);
    }
    public record PasswordChange(@NotBlank String oldPassword, @NotBlank @jakarta.validation.constraints.Size(min=10,max=72) String newPassword) {}

    public record LoginRequest(@NotBlank(message = "用户名不能为空") String username,
                               @NotBlank(message = "密码不能为空") String password) {
    }
}
