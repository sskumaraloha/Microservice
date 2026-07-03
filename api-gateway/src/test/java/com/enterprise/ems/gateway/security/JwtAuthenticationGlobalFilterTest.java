package com.enterprise.ems.gateway.security;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class JwtAuthenticationGlobalFilterTest {

    private static final String SECRET = "test-only-signing-key-at-least-32-bytes-long-for-hmac-sha256";

    private JwtAuthenticationGlobalFilter filter;
    private AtomicInteger chainInvocations;
    private GatewayFilterChain chain;

    @BeforeEach
    void setUp() {
        JwtProperties properties = new JwtProperties(SECRET, List.of("/api/v1/auth/**"));
        filter = new JwtAuthenticationGlobalFilter(new JwtValidator(properties), properties);

        chainInvocations = new AtomicInteger();
        chain = mock(GatewayFilterChain.class);
        when(chain.filter(org.mockito.ArgumentMatchers.any())).thenAnswer(invocation -> {
            chainInvocations.incrementAndGet();
            return Mono.empty();
        });
    }

    @Test
    void allowsPublicPathsThroughWithoutAToken() {
        ServerWebExchange exchange = exchangeFor("/api/v1/auth/login", null);

        filter.filter(exchange, chain).block();

        assertThat(chainInvocations.get()).isEqualTo(1);
        assertThat(exchange.getResponse().getStatusCode()).isNull();
    }

    @Test
    void rejectsAProtectedPathWithNoAuthorizationHeader() {
        ServerWebExchange exchange = exchangeFor("/api/v1/employees/42", null);

        filter.filter(exchange, chain).block();

        assertThat(chainInvocations.get()).isZero();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void rejectsAProtectedPathWithAGarbageToken() {
        ServerWebExchange exchange = exchangeFor("/api/v1/employees/42", "Bearer not-a-real-token");

        filter.filter(exchange, chain).block();

        assertThat(chainInvocations.get()).isZero();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void allowsAProtectedPathWithAValidTokenAndPropagatesUserHeadersDownstream() {
        String token = validToken();
        ServerWebExchange exchange = exchangeFor("/api/v1/employees/42", "Bearer " + token);

        filter.filter(exchange, chain).block();

        assertThat(chainInvocations.get()).isEqualTo(1);
        assertThat(exchange.getResponse().getStatusCode()).isNull();
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

    private ServerWebExchange exchangeFor(String path, String authorizationHeader) {
        MockServerHttpRequest.BaseBuilder<?> builder = MockServerHttpRequest.get(path);
        if (authorizationHeader != null) {
            builder.header(HttpHeaders.AUTHORIZATION, authorizationHeader);
        }
        return MockServerWebExchange.from(builder.build());
    }
}
