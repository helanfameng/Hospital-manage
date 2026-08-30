package com.hospital.inpatient.security;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtServiceTest {
    @Test
    void createsAndParsesSignedToken() {
        JwtService service = new JwtService("01234567890123456789012345678901", 120);
        String token = service.createToken(7, "doctor", List.of("DOCTOR"), List.of("ORDER_WRITE"));
        var claims = service.parse(token);
        assertThat(claims.getSubject()).isEqualTo("doctor");
        assertThat(service.userId(claims)).isEqualTo(7);
        assertThat(service.roles(claims)).containsExactly("DOCTOR");
        assertThat(service.permissions(claims)).containsExactly("ORDER_WRITE");
    }

    @Test
    void rejectsShortSecret() {
        assertThatThrownBy(() -> new JwtService("too-short", 10))
                .isInstanceOf(IllegalArgumentException.class);
    }
}

