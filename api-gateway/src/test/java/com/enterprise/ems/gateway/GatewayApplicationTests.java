package com.enterprise.ems.gateway;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.reactive.server.WebTestClient;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;

/**
 * Full-stack proof that the JWT filter is actually wired into the live
 * request pipeline, not just unit-tested in isolation. There is no real
 * AUTH-SERVICE/EMPLOYEE-SERVICE running (Eureka is disabled in the test
 * profile), so a request that gets PAST the JWT filter still fails to
 * route — we assert it fails with something other than 401, which is the
 * only signal the filter itself controls.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient(timeout = "10000")
@ActiveProfiles("test")
class GatewayApplicationTests {

    private static final String SECRET = "test-only-signing-key-at-least-32-bytes-long-for-hmac-sha256";

    @Autowired
    private WebTestClient webTestClient;

    @Test
    void protectedRouteWithoutATokenIsRejectedBeforeAnyRoutingIsAttempted() {
        webTestClient.get().uri("/api/v1/employees/42")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void protectedRouteWithAGarbageTokenIsRejected() {
        webTestClient.get().uri("/api/v1/employees/42")
                .header("Authorization", "Bearer garbage")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void protectedRouteWithAValidTokenPassesTheJwtFilter() {
        webTestClient.get().uri("/api/v1/employees/42")
                .header("Authorization", "Bearer " + validToken())
                .exchange()
                .expectStatus().value(status -> org.assertj.core.api.Assertions.assertThat(status)
                        .as("JWT filter should let this through; failure must come from routing, not auth")
                        .isNotEqualTo(HttpStatus.UNAUTHORIZED.value()));
    }

    @Test
    void publicAuthRoutePassesTheJwtFilterWithoutAToken() {
        webTestClient.post().uri("/api/v1/auth/login")
                .exchange()
                .expectStatus().value(status -> org.assertj.core.api.Assertions.assertThat(status)
                        .as("Login must not require a token the caller doesn't have yet")
                        .isNotEqualTo(HttpStatus.UNAUTHORIZED.value()));
    }

    private String validToken() {
        SecretKey key = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
        return Jwts.builder()
                .subject("emp-1234")
                .claim("roles", "EMPLOYEE")
                .expiration(Date.from(Instant.now().plus(15, ChronoUnit.MINUTES)))
                .signWith(key)
                .compact();
    }
}
