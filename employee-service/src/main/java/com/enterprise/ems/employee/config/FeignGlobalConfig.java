package com.enterprise.ems.employee.config;

import com.enterprise.ems.employee.security.HeaderAuthenticationFilter;
import feign.Retryer;
import feign.RequestInterceptor;
import feign.RequestTemplate;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Optional;

/**
 * Passed to {@code @EnableFeignClients(defaultConfiguration = ...)} so
 * every {@code @FeignClient} this service defines gets these beans in its
 * own isolated child context, regardless of the main application's normal
 * component scan.
 *
 * <p>{@link Retryer#NEVER_RETRY} disables Feign's own built-in retry
 * mechanism (default: up to 5 attempts) so that Resilience4j's
 * {@code @Retry} (Step 8, {@code DepartmentValidationServiceImpl}) is the
 * only thing retrying a failed call. Running both at once means every
 * Resilience4j-orchestrated attempt could silently balloon into up to 5
 * real HTTP calls of its own - two independent retry policies compounding
 * multiplicatively instead of one policy owning the decision.
 *
 * <p>Deliberately <b>not</b> annotated {@code @Configuration}: Spring Cloud
 * OpenFeign builds an isolated child {@code ApplicationContext} per client
 * from the classes passed to {@code defaultConfiguration}/
 * {@code configuration}, which is what keeps these beans from leaking into
 * every other Feign client. This class lives in
 * {@code com.enterprise.ems.employee.config} - inside the main
 * application's own component-scanned package - so an {@code @Configuration}
 * here would also register it a second time in the main context via normal
 * component scanning, defeating that isolation. Plain {@code @Bean} methods
 * still work without it (Spring processes such classes in "lite" mode);
 * the only feature lost is inter-{@code @Bean} method calls returning the
 * same singleton, which neither bean here relies on.
 */
public class FeignGlobalConfig {

    @Bean
    public Retryer feignRetryer() {
        return Retryer.NEVER_RETRY;
    }

    @Bean
    public RequestInterceptor authHeaderPropagationInterceptor() {
        return requestTemplate -> currentRequest().ifPresent(request -> {
            copyHeader(request, requestTemplate, HeaderAuthenticationFilter.USER_ID_HEADER);
            copyHeader(request, requestTemplate, HeaderAuthenticationFilter.ROLES_HEADER);
        });
    }

    private void copyHeader(HttpServletRequest source, RequestTemplate target, String headerName) {
        String value = source.getHeader(headerName);
        if (value != null) {
            target.header(headerName, value);
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
