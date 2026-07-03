package com.enterprise.ems.department.client;

import io.github.resilience4j.bulkhead.annotation.Bulkhead;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Same three-annotation shape as Employee Service's
 * {@code DepartmentValidationServiceImpl} (Step 8) - Timeout via the
 * client itself, {@link Retry} for transient failures, {@link CircuitBreaker}
 * to stop hammering a genuinely down Employee Service, {@link Bulkhead} to
 * cap concurrent in-flight lookups - with one deliberate difference: the
 * fallback here returns a value instead of throwing. See
 * {@link EmployeeHeadcountClient} for why headcount is allowed to degrade
 * to "unknown" instead of failing the whole statistics request.
 *
 * <p>{@code fallbackMethod} is on {@link Retry} (the outermost aspect),
 * not {@link CircuitBreaker}: a {@code fallbackMethod} intercepts every
 * exception the aspect it's attached to sees, so putting it on the inner
 * {@code CircuitBreaker} would return {@code null} after the very first
 * failed attempt - before {@code Retry} ever got a chance to retry.
 */
@Component
public class EmployeeHeadcountClientImpl implements EmployeeHeadcountClient {

    private static final Logger log = LoggerFactory.getLogger(EmployeeHeadcountClientImpl.class);
    private static final String INSTANCE_NAME = "employee-service";

    private final WebClient employeeServiceWebClient;

    public EmployeeHeadcountClientImpl(WebClient employeeServiceWebClient) {
        this.employeeServiceWebClient = employeeServiceWebClient;
    }

    @Override
    @CircuitBreaker(name = INSTANCE_NAME)
    @Retry(name = INSTANCE_NAME, fallbackMethod = "fallback")
    @Bulkhead(name = INSTANCE_NAME)
    public Integer getHeadcount(Long departmentId) {
        Long count = employeeServiceWebClient.get()
                .uri(uriBuilder -> uriBuilder.path("/api/v1/employees/count")
                        .queryParam("departmentId", departmentId)
                        .build())
                .retrieve()
                .bodyToMono(Long.class)
                .block();
        return count == null ? null : count.intValue();
    }

    @SuppressWarnings("unused") // invoked reflectively by Resilience4j as the Retry fallback
    private Integer fallback(Long departmentId, Throwable throwable) {
        log.warn("Employee headcount unavailable for department {}: {}", departmentId, throwable.toString());
        return null;
    }
}
