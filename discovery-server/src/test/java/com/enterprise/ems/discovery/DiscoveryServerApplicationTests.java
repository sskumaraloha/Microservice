package com.enterprise.ems.discovery;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the two production concerns this step introduces: the registry
 * boots as a Spring context, and its dashboard/REST endpoints are locked
 * down while its health endpoint stays open for orchestrator probes.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class DiscoveryServerApplicationTests {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void contextLoads() {
        // If EnableEurekaServer + security wiring is broken, the context fails to start.
    }

    @Test
    void actuatorHealthIsPubliclyAccessibleForOrchestratorProbes() {
        ResponseEntity<String> response =
                restTemplate.getForEntity(url("/actuator/health"), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"status\":\"UP\"");
    }

    @Test
    void dashboardRejectsUnauthenticatedRequests() {
        ResponseEntity<String> response = restTemplate.getForEntity(url("/"), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void dashboardAcceptsValidBasicAuthCredentials() {
        ResponseEntity<String> response = restTemplate
                .withBasicAuth("test-admin", "test-password")
                .getForEntity(url("/"), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }
}
