package com.enterprise.ems.department.config;

import com.enterprise.ems.department.security.HeaderAuthenticationFilter;
import io.netty.channel.ChannelOption;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;
import java.util.Optional;

/**
 * Employee Service is addressed by a plain, config-provided base URL
 * ({@code employee-service.base-url}) rather than an Eureka service name
 * routed through a {@code @LoadBalanced} client, unlike the Feign client
 * this same step adds on the Employee Service side
 * ({@code employee-service.client.DepartmentClient}). Both are legitimate,
 * common patterns for the same problem - shown side by side deliberately:
 * <ul>
 *   <li>Feign + Eureka: declarative, in-process client-side load balancing
 *       picks among however many instances are registered.</li>
 *   <li>WebClient + a plain URL: the platform (Docker Compose's embedded
 *       DNS today, a Kubernetes {@code Service}/service mesh sidecar
 *       tomorrow) already load-balances at the infrastructure layer, so
 *       the application doesn't need to do it again in-process.</li>
 * </ul>
 * Either is a defensible default; running both against the same
 * dependency would just be redundant.
 */
@Configuration
public class WebClientConfig {

    @Bean
    public WebClient employeeServiceWebClient(
            @Value("${employee-service.base-url:http://localhost:8081}") String baseUrl,
            @Value("${employee-service.connect-timeout-ms:2000}") int connectTimeoutMs,
            @Value("${employee-service.read-timeout-ms:3000}") int readTimeoutMs) {
        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, connectTimeoutMs)
                .responseTimeout(Duration.ofMillis(readTimeoutMs));

        return WebClient.builder()
                .baseUrl(baseUrl)
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .filter(authHeaderPropagationFilter())
                .build();
    }

    private ExchangeFilterFunction authHeaderPropagationFilter() {
        return ExchangeFilterFunction.ofRequestProcessor(request -> currentRequest()
                .map(source -> ClientRequest.from(request)
                        .headers(headers -> {
                            copyHeader(source, headers, HeaderAuthenticationFilter.USER_ID_HEADER);
                            copyHeader(source, headers, HeaderAuthenticationFilter.ROLES_HEADER);
                        })
                        .build())
                .map(Mono::just)
                .orElseGet(() -> Mono.just(request)));
    }

    private void copyHeader(HttpServletRequest source, HttpHeaders target, String headerName) {
        String value = source.getHeader(headerName);
        if (value != null) {
            target.set(headerName, value);
        }
    }

    private Optional<HttpServletRequest> currentRequest() {
        var attributes = RequestContextHolder.getRequestAttributes();
        if (attributes instanceof ServletRequestAttributes servletRequestAttributes) {
            return Optional.of(servletRequestAttributes.getRequest());
        }
        return Optional.empty();
    }
}
