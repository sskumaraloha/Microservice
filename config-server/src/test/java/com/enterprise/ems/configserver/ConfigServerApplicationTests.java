package com.enterprise.ems.configserver;

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
 * Verifies the Config Server's actual contract: it serves layered
 * per-service + shared configuration over an authenticated REST API, keeps
 * health open for probes, and transparently decrypts {@code {cipher}}
 * values before handing them to a client.
 */
// "native" must stay active here too: @ActiveProfiles replaces the profile
// list rather than appending to it, and the native (filesystem) backend
// is only wired up by Spring Cloud Config when the "native" profile is
// active — otherwise it falls back to the git backend and fails fast with
// "You need to configure a uri for the git repository."
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles({"native", "test"})
class ConfigServerApplicationTests {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void contextLoads() {
        // Fails fast if native repository, security, or encryption wiring is broken.
    }

    @Test
    void actuatorHealthIsPubliclyAccessibleForOrchestratorProbes() {
        ResponseEntity<String> response =
                restTemplate.getForEntity(url("/actuator/health"), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"status\":\"UP\"");
    }

    @Test
    void configEndpointsRejectUnauthenticatedRequests() {
        ResponseEntity<String> response =
                restTemplate.getForEntity(url("/employee-service/default"), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void servesLayeredConfigurationForEmployeeService() {
        ResponseEntity<String> response = authenticated()
                .getForEntity(url("/employee-service/default"), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        // service-specific file
        assertThat(response.getBody()).contains("\"server.port\":8081");
        // shared application.yml, proving the two sources are layered together
        assertThat(response.getBody()).contains("eureka.client.service-url.defaultZone");
    }

    @Test
    void servesDifferentPortPerServiceProvingIsolationBetweenServiceConfigs() {
        ResponseEntity<String> employee = authenticated()
                .getForEntity(url("/employee-service/default"), String.class);
        ResponseEntity<String> department = authenticated()
                .getForEntity(url("/department-service/default"), String.class);

        assertThat(employee.getBody()).contains("\"server.port\":8081");
        assertThat(department.getBody()).contains("\"server.port\":8082");
    }

    @Test
    void decryptsCipherValuesBeforeServingThemToClients() {
        ResponseEntity<String> response = authenticated()
                .getForEntity(url("/auth-service/default"), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody())
                .doesNotContain("{cipher}")
                .contains("dev-only-jwt-signing-key-rotate-per-environment-never-reuse-across-envs");
    }

    @Test
    void encryptDecryptRoundTripPreservesThePlaintext() {
        String plaintext = "round-trip-secret";

        ResponseEntity<String> encrypted = authenticated()
                .postForEntity(url("/encrypt"), plaintext, String.class);
        assertThat(encrypted.getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<String> decrypted = authenticated()
                .postForEntity(url("/decrypt"), encrypted.getBody(), String.class);
        assertThat(decrypted.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(decrypted.getBody()).isEqualTo(plaintext);
    }

    private TestRestTemplate authenticated() {
        return restTemplate.withBasicAuth("test-admin", "test-password");
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }
}
