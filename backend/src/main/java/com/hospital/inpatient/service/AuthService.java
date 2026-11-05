package com.hospital.inpatient.service;

import com.hospital.inpatient.exception.BusinessException;
import com.hospital.inpatient.mapper.AuthMapper;
import com.hospital.inpatient.security.JwtService;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class AuthService {
    private final AuthenticationManager authenticationManager;
    private final AuthMapper authMapper;
    private final JwtService jwtService;

    public AuthService(AuthenticationManager authenticationManager, AuthMapper authMapper, JwtService jwtService) {
        this.authenticationManager = authenticationManager;
        this.authMapper = authMapper;
        this.jwtService = jwtService;
    }

    public Map<String, Object> login(String username, String password) {
        try {
            authenticationManager.authenticate(new UsernamePasswordAuthenticationToken(username, password));
        } catch (RuntimeException exception) {
            throw new BusinessException("LOGIN_FAILED", "用户名或密码错误", HttpStatus.UNAUTHORIZED);
        }
        var row = authMapper.findUserByUsername(username);
        long userId = ((Number) row.get("id")).longValue();
        var roles = authMapper.findRolesByUserId(userId);
        var permissions = authMapper.findPermissionsByUserId(userId);
        authMapper.updateLastLogin(userId);
        Map<String, Object> result = new LinkedHashMap<>();
        if (!Boolean.TRUE.equals(row.get("staff_active"))) throw new BusinessException("LOGIN_FAILED", "员工已停用，请联系管理员", HttpStatus.UNAUTHORIZED);
        result.put("token", jwtService.createToken(userId, username, roles, permissions, ((Number)row.get("token_version")).intValue()));
        result.put("user", Map.of(
                "id", userId,
                "username", username,
                "displayName", row.get("display_name"),
                "roles", roles,
                "permissions", permissions
        ));
        return result;
    }

    public Map<String,Object> me(String username) {
        var row=authMapper.findUserByUsername(username);
        long id=((Number)row.get("id")).longValue();
        return Map.of("id",id,"username",username,"displayName",row.get("display_name"),
            "roles",authMapper.findRolesByUserId(id),"permissions",authMapper.findPermissionsByUserId(id));
    }
}
