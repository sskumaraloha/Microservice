package com.enterprise.ems.gateway.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtValidatorTest {

    private static final String SECRET = "test-only-signing-key-at-least-32-bytes-long-for-hmac-sha256";

    private final JwtValidator validator = new JwtValidator(new JwtProperties(SECRET, List.of()));
    private final SecretKey key = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));

    @Test
    void validatesATokenSignedWithTheSameSecretAndReturnsItsClaims() {
        String token = Jwts.builder()
                .subject("emp-1234")
                .claim("roles", "EMPLOYEE")
                .issuedAt(Date.from(Instant.now()))
                .expiration(Date.from(Instant.now().plus(15, ChronoUnit.MINUTES)))
                .signWith(key)
                .compact();

        Claims claims = validator.validate(token);

        assertThat(claims.getSubject()).isEqualTo("emp-1234");
        assertThat(claims.get("roles")).isEqualTo("EMPLOYEE");
    }

    @Test
    void rejectsAnExpiredToken() {
        String expiredToken = Jwts.builder()
                .subject("emp-1234")
                .issuedAt(Date.from(Instant.now().minus(1, ChronoUnit.HOURS)))
                .expiration(Date.from(Instant.now().minus(1, ChronoUnit.MINUTES)))
                .signWith(key)
                .compact();

        assertThatThrownBy(() -> validator.validate(expiredToken)).isInstanceOf(JwtException.class);
    }

    @Test
    void rejectsATokenSignedWithADifferentSecret() {
        SecretKey otherKey = Keys.hmacShaKeyFor(
                "a-completely-different-signing-key-nobody-shares".getBytes(StandardCharsets.UTF_8));
        String tokenSignedByAnImpostor = Jwts.builder()
                .subject("emp-1234")
                .expiration(Date.from(Instant.now().plus(15, ChronoUnit.MINUTES)))
                .signWith(otherKey)
                .compact();

        assertThatThrownBy(() -> validator.validate(tokenSignedByAnImpostor)).isInstanceOf(JwtException.class);
    }

    @Test
    void rejectsAStructurallyMalformedToken() {
        assertThatThrownBy(() -> validator.validate("not-a-jwt-at-all")).isInstanceOf(JwtException.class);
    }
}
