package com.hospital.inpatient.config;

import com.hospital.inpatient.mapper.AuthMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;

@Component
@Order(1)
public class AdminBootstrap implements ApplicationRunner {
    private final AuthMapper authMapper;
    private final PasswordEncoder passwordEncoder;
    private final String password;

    public AdminBootstrap(
            AuthMapper authMapper,
            PasswordEncoder passwordEncoder,
            @Value("${hospital.bootstrap.admin-password:}") String password
    ) {
        this.authMapper = authMapper;
        this.passwordEncoder = passwordEncoder;
        this.password = password;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (authMapper.countUsers() > 0 || password == null || password.isBlank()) {
            return;
        }
        create("admin", "系统管理员", "ADM001", "ADMIN");
        create("admission", "住院处人员", "INP001", "ADMISSION");
        create("doctor", "心内科医生", "DOC001", "DOCTOR");
        create("nurse", "心内科护士", "NUR001", "NURSE");
        create("pharmacy", "药房人员", "PHA001", "PHARMACY");
        create("cashier", "收费人员", "CAS001", "CASHIER");
    }

    private void create(String username, String displayName, String staffNo, String role) {
        var values = new HashMap<String, Object>();
        values.put("username", username);
        values.put("displayName", displayName);
        values.put("staffNo", staffNo);
        values.put("passwordHash", passwordEncoder.encode(password));
        authMapper.insertBootstrapUser(values);
        authMapper.assignRole(((Number) values.get("id")).longValue(), role);
    }
}
