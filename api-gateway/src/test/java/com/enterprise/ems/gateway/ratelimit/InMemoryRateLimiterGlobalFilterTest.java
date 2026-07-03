package com.enterprise.ems.gateway.ratelimit;

import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class InMemoryRateLimiterGlobalFilterTest {

    @Test
    void allowsUpToTheConfiguredLimitThenRejectsWith429FromTheSameClient() {
        // A 10s refresh window keeps the whole burst inside a single window
        // regardless of how long the test machine takes to run these lines.
        InMemoryRateLimiterGlobalFilter filter =
                new InMemoryRateLimiterGlobalFilter(new RateLimitProperties(3, Duration.ofSeconds(10)));

        AtomicInteger chainInvocations = new AtomicInteger();
        GatewayFilterChain chain = mock(GatewayFilterChain.class);
        // Mono.defer: the counter must only increment on SUBSCRIBE, matching
        // the real GatewayFilterChain contract (nothing happens until the
        // reactive pipeline is subscribed to). A naive `thenAnswer` that
        // increments eagerly would falsely "pass" every call regardless of
        // whether RateLimiterOperator ever let the subscription through.
        when(chain.filter(org.mockito.ArgumentMatchers.any()))
                .thenAnswer(invocation -> Mono.defer(() -> {
                    chainInvocations.incrementAndGet();
                    return Mono.empty();
                }));

        HttpStatus[] statuses = new HttpStatus[5];
        for (int i = 0; i < statuses.length; i++) {
            ServerWebExchange exchange = exchangeFromClient("203.0.113.10", 5000 + i);
            filter.filter(exchange, chain).block();
            statuses[i] = (HttpStatus) exchange.getResponse().getStatusCode();
        }

        assertThat(chainInvocations.get()).isEqualTo(3);
        assertThat(statuses).containsExactly(null, null, null, HttpStatus.TOO_MANY_REQUESTS, HttpStatus.TOO_MANY_REQUESTS);
    }

    @Test
    void tracksLimitsSeparatelyPerClientIp() {
        InMemoryRateLimiterGlobalFilter filter =
                new InMemoryRateLimiterGlobalFilter(new RateLimitProperties(1, Duration.ofSeconds(10)));

        GatewayFilterChain chain = mock(GatewayFilterChain.class);
        when(chain.filter(org.mockito.ArgumentMatchers.any())).thenReturn(Mono.empty());

        ServerWebExchange clientA = exchangeFromClient("203.0.113.10", 6000);
        ServerWebExchange clientAAgain = exchangeFromClient("203.0.113.10", 6001);
        ServerWebExchange clientB = exchangeFromClient("203.0.113.20", 6002);

        filter.filter(clientA, chain).block();
        filter.filter(clientAAgain, chain).block();
        filter.filter(clientB, chain).block();

        assertThat(clientA.getResponse().getStatusCode()).isNull();
        assertThat(clientAAgain.getResponse().getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(clientB.getResponse().getStatusCode()).isNull();
    }

    private ServerWebExchange exchangeFromClient(String ip, int port) {
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/v1/employees")
                .remoteAddress(new InetSocketAddress(ip, port))
                .build();
        return MockServerWebExchange.from(request);
    }
}
