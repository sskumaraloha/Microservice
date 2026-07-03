package com.enterprise.ems.department.client;

import com.enterprise.ems.department.dto.DepartmentRequest;
import com.enterprise.ems.department.dto.DepartmentResponse;
import com.enterprise.ems.department.dto.DepartmentStatisticsResponse;
import com.enterprise.ems.department.security.HeaderAuthenticationFilter;
import com.github.tomakehurst.wiremock.WireMockServer;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises the real {@link EmployeeHeadcountClientImpl}, the real
 * Resilience4j decoration, and the real header-propagation filter
 * together against an actual HTTP server (WireMock standing in for
 * Employee Service) - nothing here is mocked at the Java level, only the
 * network endpoint is substituted, via {@code employee-service.base-url}
 * (see {@link com.enterprise.ems.department.config.WebClientConfig}).
 *
 * <p>Every scenario goes through the real
 * {@code GET /api/v1/departments/{id}/statistics} endpoint rather than
 * calling {@link EmployeeHeadcountClient} directly: the auth-header
 * propagation filter reads the inbound request via
 * {@code RequestContextHolder}, which only exists inside an active HTTP
 * request - calling the client bean directly from a test method would
 * silently skip that filter instead of exercising it.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class EmployeeHeadcountClientWireMockTest {

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
    static void employeeServiceUrl(DynamicPropertyRegistry registry) {
        registry.add("employee-service.base-url", () -> "http://localhost:" + wireMockServer.port());
    }

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private CircuitBreakerRegistry circuitBreakerRegistry;

    @BeforeEach
    void resetStubsAndCircuitBreaker() {
        wireMockServer.resetAll();
        // Same reasoning as Employee Service's equivalent test (Step 8):
        // the "employee-service" CircuitBreaker is a singleton shared by
        // every test method in this class, and transitionToClosedState()
        // alone does not clear a lingering sliding window when the
        // breaker is already CLOSED.
        var circuitBreaker = circuitBreakerRegistry.circuitBreaker("employee-service");
        circuitBreaker.transitionToForcedOpenState();
        circuitBreaker.transitionToClosedState();
    }

    @Test
    void aRespondingEmployeeServiceReportsAHeadcountAndReceivesThePropagatedIdentity() {
        Long departmentId = createDepartment("HC-Success");
        wireMockServer.stubFor(get(urlPathEqualTo("/api/v1/employees/count"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody("5")));

        DepartmentStatisticsResponse stats = getStatistics(departmentId);

        assertThat(stats.employeeHeadcount()).isEqualTo(5);
        wireMockServer.verify(1, getRequestedFor(urlPathEqualTo("/api/v1/employees/count"))
                .withHeader(HeaderAuthenticationFilter.USER_ID_HEADER, equalTo("1"))
                .withHeader(HeaderAuthenticationFilter.ROLES_HEADER, equalTo("ADMIN")));
    }

    @Test
    void repeatedInfrastructureFailuresAreRetriedThenDegradeToANullHeadcount() {
        Long departmentId = createDepartment("HC-AlwaysFails");
        wireMockServer.stubFor(get(urlPathEqualTo("/api/v1/employees/count")).willReturn(aResponse().withStatus(500)));

        DepartmentStatisticsResponse stats = getStatistics(departmentId);

        // Headcount is best-effort: an unreachable Employee Service must
        // never fail the whole statistics request, only report "unknown"
        // (see EmployeeHeadcountClient).
        assertThat(stats.employeeHeadcount()).isNull();
        // resilience4j.retry.instances.employee-service.max-attempts = 3
        // in application-test.yml: the first attempt plus two retries.
        wireMockServer.verify(3, getRequestedFor(urlPathEqualTo("/api/v1/employees/count")));
    }

    @Test
    void aTransientFailureThatRecoversWithinTheRetryBudgetStillReportsAHeadcount() {
        Long departmentId = createDepartment("HC-Recovers");
        wireMockServer.stubFor(get(urlPathEqualTo("/api/v1/employees/count"))
                .inScenario("flaky-employee-service")
                .whenScenarioStateIs(com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED)
                .willReturn(aResponse().withStatus(500))
                .willSetStateTo("recovered"));
        wireMockServer.stubFor(get(urlPathEqualTo("/api/v1/employees/count"))
                .inScenario("flaky-employee-service")
                .whenScenarioStateIs("recovered")
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody("3")));

        DepartmentStatisticsResponse stats = getStatistics(departmentId);

        assertThat(stats.employeeHeadcount()).isEqualTo(3);
        wireMockServer.verify(2, getRequestedFor(urlPathEqualTo("/api/v1/employees/count")));
    }

    private Long createDepartment(String namePrefix) {
        DepartmentRequest request = new DepartmentRequest(
                namePrefix + "-" + System.nanoTime(), "C" + System.nanoTime() % 100000, null, null, null);
        ResponseEntity<DepartmentResponse> response = exchange("/api/v1/departments", HttpMethod.POST, request,
                DepartmentResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody().id();
    }

    private DepartmentStatisticsResponse getStatistics(Long departmentId) {
        ResponseEntity<DepartmentStatisticsResponse> response = exchange(
                "/api/v1/departments/" + departmentId + "/statistics", HttpMethod.GET, null,
                DepartmentStatisticsResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    private <T> ResponseEntity<T> exchange(String url, HttpMethod method, Object body, Class<T> responseType) {
        HttpHeaders headers = new HttpHeaders();
        headers.add(HeaderAuthenticationFilter.USER_ID_HEADER, "1");
        headers.add(HeaderAuthenticationFilter.ROLES_HEADER, "ADMIN");
        return restTemplate.exchange(url, method, new HttpEntity<>(body, headers), responseType);
    }
}
