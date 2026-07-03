package com.enterprise.ems.employee.client;

import com.enterprise.ems.employee.dto.EmployeeRequest;
import com.enterprise.ems.employee.security.HeaderAuthenticationFilter;
import com.github.tomakehurst.wiremock.WireMockServer;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.apache.hc.client5.http.impl.DefaultHttpRequestRetryStrategy;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.util.TimeValue;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.web.client.RestTemplateCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.LocalDate;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises the real Feign client, the real Resilience4j-decorated
 * {@code DepartmentValidationServiceImpl}, and the real auth-header
 * propagation interceptor together against an actual HTTP server
 * (WireMock standing in for Department Service) — nothing here is
 * mocked at the Java level, only the network endpoint is substituted.
 * {@code department-service.base-url} (see {@link DepartmentClient})
 * points Feign at WireMock instead of Eureka-based discovery.
 *
 * <p>{@link NoAutoRetryRestTemplateConfig} strips out a completely
 * unrelated retry mechanism that would otherwise contaminate these
 * assertions: {@code spring-cloud-netflix-eureka-client} pulls in Apache
 * HttpClient 5, which {@code TestRestTemplate} then auto-selects, and
 * HttpClient 5's own {@code DefaultHttpRequestRetryStrategy} silently
 * retries any request whose response is 503 or 429 - regardless of HTTP
 * method. That is a second, client-side retry loop stacked on top of the
 * server-side one this test is trying to measure: found by watching the
 * "500 always fails" test receive 5 requests instead of 3, tracing the
 * extra 2 back to a whole second POST (new Tomcat thread, fresh
 * connection) arriving exactly one second after the first 503 was
 * returned, and finally seeing {@code HttpRequestRetryExec ... wait for 1
 * SECONDS} in HttpClient 5's own debug log.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(DepartmentValidationServiceWireMockTest.NoAutoRetryRestTemplateConfig.class)
class DepartmentValidationServiceWireMockTest {

    @TestConfiguration
    static class NoAutoRetryRestTemplateConfig {
        @Bean
        RestTemplateCustomizer disableHttpClientAutomaticRetries() {
            return restTemplate -> restTemplate.setRequestFactory(new HttpComponentsClientHttpRequestFactory(
                    HttpClients.custom()
                            .setRetryStrategy(new DefaultHttpRequestRetryStrategy(0, TimeValue.ZERO_MILLISECONDS))
                            .build()));
        }
    }

    private static WireMockServer wireMockServer;

    @BeforeAll
    static void startWireMock() {
        wireMockServer = new WireMockServer(0);
        wireMockServer.start();
    }

    @AfterAll
    static void stopWireMock() {
        wireMockServer.stop();
    }

    @DynamicPropertySource
    static void departmentServiceUrl(DynamicPropertyRegistry registry) {
        registry.add("department-service.base-url", () -> "http://localhost:" + wireMockServer.port());
    }

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private CircuitBreakerRegistry circuitBreakerRegistry;

    @BeforeEach
    void resetStubsAndCircuitBreaker() {
        wireMockServer.resetAll();
        // The "department-service" CircuitBreaker is a singleton shared by
        // every test method in this class (they all share one Spring
        // context) — without resetting it, a failure injected by one test
        // trips the breaker for every test that runs after it, regardless
        // of which department id or WireMock stub is involved. Discovered
        // by running this test, not anticipated up front.
        //
        // transitionToClosedState() alone is not enough: calling it while
        // already CLOSED is a no-op that leaves the sliding window's
        // recorded calls in place (confirmed empirically — a lingering
        // single failure from one test was still enough to help tip the
        // next test's window over its failure threshold early). Forcing a
        // real state change first (CLOSED -> FORCED_OPEN -> CLOSED) is what
        // actually clears the metrics.
        var circuitBreaker = circuitBreakerRegistry.circuitBreaker("department-service");
        circuitBreaker.transitionToForcedOpenState();
        circuitBreaker.transitionToClosedState();
    }

    @Test
    void anExistingDepartmentAllowsEmployeeCreationAndReceivesThePropagatedIdentity() {
        wireMockServer.stubFor(get(urlEqualTo("/api/v1/departments/1"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody("{\"id\":1,\"name\":\"Engineering\"}")));

        ResponseEntity<String> response = createEmployee(1L, "wiremock1@example.com");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        wireMockServer.verify(1, getRequestedFor(urlEqualTo("/api/v1/departments/1"))
                .withHeader(HeaderAuthenticationFilter.USER_ID_HEADER, equalTo("1"))
                .withHeader(HeaderAuthenticationFilter.ROLES_HEADER, equalTo("ADMIN")));
    }

    @Test
    void aMissingDepartmentIsRejectedWithoutAnyRetries() {
        wireMockServer.stubFor(get(urlEqualTo("/api/v1/departments/999")).willReturn(aResponse().withStatus(404)));

        ResponseEntity<String> response = createEmployee(999L, "wiremock2@example.com");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        // A 404 is a valid business answer ("no such department"), not an
        // infrastructure failure — it must never trigger a retry.
        wireMockServer.verify(1, getRequestedFor(urlEqualTo("/api/v1/departments/999")));
    }

    @Test
    void repeatedInfrastructureFailuresAreRetriedThenSurfaceAsServiceUnavailable() {
        wireMockServer.stubFor(get(urlEqualTo("/api/v1/departments/2")).willReturn(aResponse().withStatus(500)));

        ResponseEntity<String> response = createEmployee(2L, "wiremock3@example.com");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        // resilience4j.retry.instances.department-service.max-attempts = 3
        // in application-test.yml: the first attempt plus two retries.
        wireMockServer.verify(3, getRequestedFor(urlEqualTo("/api/v1/departments/2")));
    }

    @Test
    void aTransientFailureThatRecoversWithinTheRetryBudgetStillSucceeds() {
        wireMockServer.stubFor(get(urlEqualTo("/api/v1/departments/3"))
                .inScenario("flaky-department-service")
                .whenScenarioStateIs(com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED)
                .willReturn(aResponse().withStatus(500))
                .willSetStateTo("recovered"));
        wireMockServer.stubFor(get(urlEqualTo("/api/v1/departments/3"))
                .inScenario("flaky-department-service")
                .whenScenarioStateIs("recovered")
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody("{\"id\":3,\"name\":\"Sales\"}")));

        ResponseEntity<String> response = createEmployee(3L, "wiremock4@example.com");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        wireMockServer.verify(2, getRequestedFor(urlEqualTo("/api/v1/departments/3")));
    }

    private ResponseEntity<String> createEmployee(Long departmentId, String email) {
        HttpHeaders headers = new HttpHeaders();
        headers.add(HeaderAuthenticationFilter.USER_ID_HEADER, "1");
        headers.add(HeaderAuthenticationFilter.ROLES_HEADER, "ADMIN");

        EmployeeRequest request = new EmployeeRequest("Ada", "Lovelace", email, null, null, departmentId,
                LocalDate.of(2020, 1, 1));

        return restTemplate.exchange("/api/v1/employees", HttpMethod.POST, new HttpEntity<>(request, headers),
                String.class);
    }
}
