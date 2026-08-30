package com.hospital.inpatient.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.Date;
import java.util.List;

@Service
public class JwtService {
    private final SecretKey key;
    private final long expirationMinutes;

    public JwtService(
            @Value("${hospital.jwt.secret}") String secret,
            @Value("${hospital.jwt.expiration-minutes}") long expirationMinutes
    ) {
        if (secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalArgumentException("HOSPITAL_JWT_SECRET must contain at least 32 bytes");
        }
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.expirationMinutes = expirationMinutes;
    }

    public String createToken(long userId, String username, Collection<String> roles, Collection<String> permissions) {
        return createToken(userId, username, roles, permissions, 0);
    }

    public String createToken(long userId, String username, Collection<String> roles, Collection<String> permissions, int version) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(username)
                .claim("uid", userId)
                .claim("ver", version)
                .claim("roles", roles)
                .claim("permissions", permissions)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(expirationMinutes, ChronoUnit.MINUTES)))
                .signWith(key)
                .compact();
    }

    public Claims parse(String token) {
        return Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
    }

    public long userId(Claims claims) {
        return ((Number) claims.get("uid")).longValue();
    }

    @SuppressWarnings("unchecked")
    public List<String> roles(Claims claims) {
        return (List<String>) claims.getOrDefault("roles", List.of());
    }

    @SuppressWarnings("unchecked")
    public List<String> permissions(Claims claims) {
        return (List<String>) claims.getOrDefault("permissions", List.of());
    }
}
