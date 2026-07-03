package com.enterprise.ems.employee.service.impl;

import com.enterprise.ems.employee.client.DepartmentClient;
import com.enterprise.ems.employee.exception.DepartmentServiceUnavailableException;
import com.enterprise.ems.employee.service.DepartmentValidationService;
import feign.FeignException;
import io.github.resilience4j.bulkhead.annotation.Bulkhead;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * How the six resilience concepts from this course's syllabus map onto
 * this one integration:
 * <ul>
 *   <li><b>Timeout</b> — Feign's own connect/read timeouts
 *       ({@code feign.client.config.department-service.*}), not
 *       Resilience4j's {@code TimeLimiter} (designed for
 *       {@code CompletableFuture}-returning methods; forcing it onto a
 *       plain blocking call would fight the framework instead of using it).</li>
 *   <li><b>Retry</b> — {@link Retry}, only for calls that actually threw
 *       (a 404 is caught and returned as {@code false} below, so it is
 *       never seen as a failure and never retried).</li>
 *   <li><b>Circuit Breaker</b> — {@link CircuitBreaker}, same reasoning:
 *       repeated infrastructure failures open the circuit; repeated
 *       legitimate 404s never do.</li>
 *   <li><b>Bulkhead</b> — {@link Bulkhead}, caps concurrent in-flight
 *       calls to Department Service so a slow dependency can't exhaust
 *       this service's own request-handling threads.</li>
 *   <li><b>Fallback</b> — {@link #fallback}, converts the exhausted-retries/
 *       open-circuit case into a distinct, honest 503
 *       ({@link DepartmentServiceUnavailableException}), never a false
 *       "department not found." Attached to {@link Retry} rather than
 *       {@link CircuitBreaker}: a {@code fallbackMethod} intercepts
 *       <em>every</em> exception the annotated aspect sees, so putting it
 *       on the inner {@code CircuitBreaker} would fire it on each
 *       individual failed attempt (converting the raw Feign exception
 *       before {@code Retry}, which wraps it, ever saw one) instead of
 *       once, after every attempt this call is willing to make is
 *       actually exhausted.</li>
 *   <li><b>Rate Limiter</b> — already built, at the API Gateway (Step 4),
 *       protecting every downstream service from the client side; adding
 *       a second one on this specific outbound call would duplicate that
 *       protection without a distinct purpose.</li>
 * </ul>
 */
@Service
public class DepartmentValidationServiceImpl implements DepartmentValidationService {

    private static final Logger log = LoggerFactory.getLogger(DepartmentValidationServiceImpl.class);
    private static final String INSTANCE_NAME = "department-service";

    private final DepartmentClient departmentClient;

    public DepartmentValidationServiceImpl(DepartmentClient departmentClient) {
        this.departmentClient = departmentClient;
    }

    @Override
    @CircuitBreaker(name = INSTANCE_NAME)
    @Retry(name = INSTANCE_NAME, fallbackMethod = "fallback")
    @Bulkhead(name = INSTANCE_NAME)
    public boolean departmentExists(Long departmentId) {
        try {
            departmentClient.getById(departmentId);
            return true;
        } catch (FeignException.NotFound e) {
            return false;
        }
    }

    @SuppressWarnings("unused") // invoked reflectively by Resilience4j as the Retry fallback
    private boolean fallback(Long departmentId, Throwable throwable) {
        log.warn("Department Service unavailable while validating department {}: {}", departmentId, throwable.toString());
        throw new DepartmentServiceUnavailableException(departmentId, throwable);
    }
}
