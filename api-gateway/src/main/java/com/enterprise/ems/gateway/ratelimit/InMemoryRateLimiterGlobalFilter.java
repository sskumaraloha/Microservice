package com.enterprise.ems.gateway.ratelimit;

import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import io.github.resilience4j.ratelimiter.RequestNotPermitted;
import io.github.resilience4j.reactor.ratelimiter.operator.RateLimiterOperator;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Per-client-IP, per-gateway-instance rate limiting. This is intentionally
 * NOT cluster-wide: with N gateway replicas, a client could get up to
 * N times the configured limit by being routed to different instances.
 * Step 10 replaces the in-memory {@link RateLimiterRegistry} here with a
 * Redis-backed limiter so the limit holds cluster-wide; the filter's
 * external behavior (429 on rejection) does not change.
 *
 * <p>Known trade-off: a {@link RateLimiter} is created per distinct client
 * IP and never evicted, so a very large number of distinct clients grows
 * this registry's memory over the process lifetime. Acceptable for course
 * purposes and for small deployments; the Redis-backed replacement in
 * Step 10 solves this too, since Redis keys carry a TTL.
 */
@Component
public class InMemoryRateLimiterGlobalFilter implements GlobalFilter, Ordered {

    private final RateLimiterRegistry rateLimiterRegistry;

    public InMemoryRateLimiterGlobalFilter(RateLimitProperties properties) {
        RateLimiterConfig config = RateLimiterConfig.custom()
                .limitForPeriod(properties.limitForPeriod())
                .limitRefreshPeriod(properties.refreshPeriod())
                .timeoutDuration(Duration.ZERO) // never queue a rejected request; fail fast with 429
                .build();
        this.rateLimiterRegistry = RateLimiterRegistry.of(config);
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        RateLimiter limiter = rateLimiterRegistry.rateLimiter(clientKey(exchange));

        return chain.filter(exchange)
                .transformDeferred(RateLimiterOperator.of(limiter))
                .onErrorResume(RequestNotPermitted.class, ex -> tooManyRequests(exchange));
    }

    @Override
    public int getOrder() {
        // Runs before JWT verification: reject abusive traffic before spending
        // any CPU on cryptographic signature checks.
        return Ordered.HIGHEST_PRECEDENCE;
    }

    private String clientKey(ServerWebExchange exchange) {
        InetSocketAddress remoteAddress = exchange.getRequest().getRemoteAddress();
        return remoteAddress != null && remoteAddress.getAddress() != null
                ? remoteAddress.getAddress().getHostAddress()
                : "unknown";
    }

    private Mono<Void> tooManyRequests(ServerWebExchange exchange) {
        exchange.getResponse().setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        String body = """
                {"status":429,"error":"Too Many Requests","message":"Rate limit exceeded"}""";
        DataBuffer buffer = exchange.getResponse().bufferFactory().wrap(body.getBytes(StandardCharsets.UTF_8));
        return exchange.getResponse().writeWith(Mono.just(buffer));
    }
}
