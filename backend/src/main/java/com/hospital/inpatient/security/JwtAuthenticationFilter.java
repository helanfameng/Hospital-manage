package com.hospital.inpatient.security;

import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.ArrayList;

@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {
    private final JwtService jwtService;
    private final com.hospital.inpatient.mapper.AuthMapper authMapper;

    public JwtAuthenticationFilter(JwtService jwtService, com.hospital.inpatient.mapper.AuthMapper authMapper) {
        this.jwtService = jwtService;
        this.authMapper = authMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String authorization = request.getHeader("Authorization");
        if (authorization != null && authorization.startsWith("Bearer ")) {
            try {
                Claims claims = jwtService.parse(authorization.substring(7));
                var row = authMapper.findUserByUsername(claims.getSubject());
                if (row==null || !Boolean.TRUE.equals(row.get("enabled")) || !Boolean.TRUE.equals(row.get("staff_active")) ||
                    ((Number)row.get("id")).longValue()!=jwtService.userId(claims) ||
                    ((Number)row.get("token_version")).intValue()!=((Number)claims.getOrDefault("ver",0)).intValue()) {
                    throw new IllegalArgumentException("Account or token is no longer active");
                }
                var roles = authMapper.findRolesByUserId(jwtService.userId(claims));
                var permissions = authMapper.findPermissionsByUserId(jwtService.userId(claims));
                var authorities = new ArrayList<SimpleGrantedAuthority>();
                roles.forEach(role -> authorities.add(new SimpleGrantedAuthority("ROLE_" + role)));
                permissions.forEach(permission -> authorities.add(new SimpleGrantedAuthority(permission)));
                var authentication = new UsernamePasswordAuthenticationToken(
                        new CurrentUser(jwtService.userId(claims), claims.getSubject(), roles,
                            row.get("staff_id")==null ? null : ((Number)row.get("staff_id")).longValue(),
                            row.get("department_id")==null ? null : ((Number)row.get("department_id")).longValue()),
                        null,
                        authorities
                );
                SecurityContextHolder.getContext().setAuthentication(authentication);
            } catch (RuntimeException ignored) {
                SecurityContextHolder.clearContext();
            }
        }
        chain.doFilter(request, response);
    }
}
