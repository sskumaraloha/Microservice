package com.enterprise.ems.gateway.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * Applies to every request that matches a configured Gateway route (NOT to
 * this application's own actuator endpoints, which never enter the Gateway
 * filter chain at all). Public routes — login, register, token refresh —
 * are exempt by path pattern since a caller can't present a token before
 * they have one.
 */
@Component
public class JwtAuthenticationGlobalFilter implements GlobalFilter, Ordered {

    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtValidator jwtValidator;
    private final JwtProperties jwtProperties;
    private final AntPathMatcher pathMatcher = new AntPathMatcher();

    public JwtAuthenticationGlobalFilter(JwtValidator jwtValidator, JwtProperties jwtProperties) {
        this.jwtValidator = jwtValidator;
        this.jwtProperties = jwtProperties;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getURI().getPath();
        if (isPublic(path)) {
            return chain.filter(exchange);
        }

        String authHeader = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (authHeader == null || !authHeader.startsWith(BEARER_PREFIX)) {
            return unauthorized(exchange, "Missing bearer token");
        }

        String token = authHeader.substring(BEARER_PREFIX.length());
        try {
            Claims claims = jwtValidator.validate(token);
            ServerWebExchange mutated = exchange.mutate()
                    .request(builder -> builder
                            // Downstream services trust these headers because, on the
                            // private network, ONLY the gateway can set them — every
                            // service must be deployed so it is unreachable except via
                            // the gateway (Step 20's Kubernetes NetworkPolicy enforces this).
                            .header("X-Auth-User-Id", claims.getSubject())
                            .header("X-Auth-Roles", rolesOf(claims)))
                    .build();
            return chain.filter(mutated);
        } catch (JwtException e) {
            return unauthorized(exchange, "Invalid or expired token");
        }
    }

    @Override
    public int getOrder() {
        return -1;
    }

    private boolean isPublic(String path) {
        return jwtProperties.publicPaths().stream().anyMatch(pattern -> pathMatcher.match(pattern, path));
    }

    private String rolesOf(Claims claims) {
        return Optional.ofNullable(claims.get("roles")).map(String::valueOf).orElse("");
    }

    private Mono<Void> unauthorized(ServerWebExchange exchange, String message) {
        exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        String body = """
                {"status":401,"error":"Unauthorized","message":"%s"}""".formatted(message);
        DataBuffer buffer = exchange.getResponse().bufferFactory().wrap(body.getBytes(StandardCharsets.UTF_8));
        return exchange.getResponse().writeWith(Mono.just(buffer));
    }
}
