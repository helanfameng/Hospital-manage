package com.hospital.inpatient.service;

import com.hospital.inpatient.exception.BusinessException;
import com.hospital.inpatient.mapper.AdminMapper;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class AdminService {
    private final AdminMapper mapper;
    private final PasswordEncoder encoder;
    private final AuditService auditService;
    private final AccountGuard guard;

    public AdminService(AdminMapper mapper, PasswordEncoder encoder, AuditService auditService, AccountGuard guard) {
        this.mapper = mapper;
        this.encoder = encoder;
        this.auditService = auditService;
        this.guard = guard;
    }

    @Transactional
    public Map<String, Object> createUser(long staffId, String username, String displayName,
                                          String password, List<String> roles) {
        validatePassword(password);
        if (guard.count("SELECT COUNT(*) FROM staff WHERE id=? AND active=TRUE",staffId)!=1) throw new BusinessException("INVALID_STAFF", "员工不存在或已停用");
        if (roles==null || roles.isEmpty() || roles.stream().distinct().count()!=roles.size()) throw new BusinessException("INVALID_ROLES", "请至少选择一个角色且不能重复");
        for (String role : roles) if (guard.count("SELECT COUNT(*) FROM role WHERE code=? AND active=TRUE",role)!=1) throw new BusinessException("INVALID_ROLES", "角色不存在或已停用");
        var values = new HashMap<String, Object>();
        values.put("staffId", staffId);
        values.put("username", username);
        values.put("displayName", displayName);
        values.put("passwordHash", encoder.encode(password));
        mapper.insertUser(values);
        long id = ((Number) values.get("id")).longValue();
        roles.forEach(role -> mapper.assignUserRole(id, role));
        auditService.success("USER_CREATE", "user_account", id, null,
                Map.of("username", username, "displayName", displayName, "roles", roles));
        return Map.of("id", id, "username", username, "displayName", displayName, "roles", roles);
    }

    @Transactional
    public void setEnabled(long id, boolean enabled) {
        // Serialize administrator status changes before locking the individual target.
        guard.jdbc.queryForList("SELECT ua.id FROM user_account ua JOIN user_role ur ON ur.user_id=ua.id JOIN role r ON r.id=ur.role_id WHERE r.code='ADMIN' ORDER BY ua.id FOR UPDATE");
        var before = guard.row("SELECT id,username,enabled FROM user_account WHERE id=? FOR UPDATE",id);
        if (!enabled && id==com.hospital.inpatient.security.SecurityUtils.currentUser().id()) throw new BusinessException("SELF_DISABLE", "不能停用当前登录账号");
        if (!enabled && guard.count("SELECT COUNT(*) FROM user_role ur JOIN role r ON r.id=ur.role_id WHERE ur.user_id=? AND r.code='ADMIN'",id)>0 &&
            guard.count("SELECT COUNT(*) FROM user_account ua JOIN user_role ur ON ur.user_id=ua.id JOIN role r ON r.id=ur.role_id WHERE r.code='ADMIN' AND r.active=TRUE AND ua.enabled=TRUE AND ua.id<>?",id)==0) {
            throw new BusinessException("LAST_ADMIN", "不能停用最后一个有效管理员");
        }
        guard.jdbc.update("UPDATE user_account SET enabled=?,token_version=token_version+1 WHERE id=?",enabled,id);
        auditService.success("USER_STATUS", "user_account",id,before,Map.of("enabled",enabled));
    }

    @Transactional
    public void changePassword(String oldPassword, String newPassword) {
        validatePassword(newPassword);
        long id = com.hospital.inpatient.security.SecurityUtils.currentUser().id();
        var row = guard.row("SELECT password_hash FROM user_account WHERE id=? FOR UPDATE",id);
        if (!encoder.matches(oldPassword,(String)row.get("password_hash"))) throw new BusinessException("PASSWORD_MISMATCH", "原密码不正确");
        if (encoder.matches(newPassword,(String)row.get("password_hash"))) throw new BusinessException("PASSWORD_UNCHANGED", "新密码不能与原密码相同");
        guard.jdbc.update("UPDATE user_account SET password_hash=?,password_changed_at=UTC_TIMESTAMP(3),token_version=token_version+1 WHERE id=?",encoder.encode(newPassword),id);
        auditService.success("PASSWORD_CHANGE","user_account",id,null,Map.of("changed",true));
    }

    private void validatePassword(String password) {
        if (password==null || password.length()<10 || password.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>72) {
            throw new BusinessException("WEAK_PASSWORD", "密码至少10个字符，且 UTF-8 编码不超过72字节");
        }
    }
}
