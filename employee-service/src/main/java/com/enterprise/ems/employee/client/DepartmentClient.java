package com.enterprise.ems.employee.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

/**
 * {@code name = "department-service"} is resolved through Eureka (Step 2)
 * exactly like the Gateway's {@code lb://DEPARTMENT-SERVICE} routes
 * (Step 4) — Feign's Spring Cloud integration applies the same
 * client-side load balancing automatically, just one layer earlier in the
 * call chain. The identity-propagating {@code RequestInterceptor} and the
 * disabled built-in Feign retryer (see {@code config.FeignGlobalConfig})
 * are registered as {@code @EnableFeignClients(defaultConfiguration=...)}
 * so every Feign client this service ever adds gets the same identity
 * propagation and the same single (Resilience4j-managed) retry policy.
 *
 * <p>{@code url} is blank in every real environment, which leaves Feign's
 * normal Eureka-backed load balancing in charge; it exists purely so
 * tests can point this client at a WireMock instance instead (see
 * {@code DepartmentValidationServiceWireMockTest}) without needing a real
 * Discovery Server.
 */
@FeignClient(name = "department-service", url = "${department-service.base-url:}")
public interface DepartmentClient {

    @GetMapping("/api/v1/departments/{id}")
    DepartmentSummary getById(@PathVariable("id") Long id);
}
