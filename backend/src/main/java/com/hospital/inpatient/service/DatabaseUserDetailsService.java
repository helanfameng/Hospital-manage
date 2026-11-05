package com.hospital.inpatient.service;

import com.hospital.inpatient.mapper.AuthMapper;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

@Service
public class DatabaseUserDetailsService implements UserDetailsService {
    private final AuthMapper authMapper;

    public DatabaseUserDetailsService(AuthMapper authMapper) {
        this.authMapper = authMapper;
    }

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        var row = authMapper.findUserByUsername(username);
        if (row == null) {
            throw new UsernameNotFoundException("账号不存在");
        }
        long id = ((Number) row.get("id")).longValue();
        var authorities = authMapper.findRolesByUserId(id).stream()
                .map(role -> new SimpleGrantedAuthority("ROLE_" + role))
                .collect(java.util.stream.Collectors.toCollection(java.util.ArrayList::new));
        authMapper.findPermissionsByUserId(id).stream()
                .map(SimpleGrantedAuthority::new)
                .forEach(authorities::add);
        return User.withUsername(String.valueOf(row.get("username")))
                .password(String.valueOf(row.get("password_hash")))
                .disabled(!Boolean.TRUE.equals(row.get("enabled")))
                .authorities(authorities)
                .build();
    }
}

