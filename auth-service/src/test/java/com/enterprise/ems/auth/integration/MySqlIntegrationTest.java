package com.enterprise.ems.auth.integration;

import com.enterprise.ems.auth.dto.LoginRequest;
import com.enterprise.ems.auth.dto.RegisterRequest;
import com.enterprise.ems.auth.dto.TokenResponse;
import com.enterprise.ems.auth.dto.UserResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The H2-backed {@link AuthServiceApplicationIntegrationTest} runs on every
 * build and covers the full auth flow; this class re-validates the same
 * Flyway migrations and the basic register/login path against a REAL
 * MySQL, catching any MySQL-specific SQL that happens to also be valid
 * H2-in-MySQL-mode syntax but would fail against the real database engine
 * this service runs on in every non-test environment.
 *
 * <p>{@code disabledWithoutDocker = true} skips this entire class when no
 * Docker daemon is reachable (e.g. this course's sandboxed dev
 * environment) instead of failing the build — a real CI pipeline runs
 * with Docker available and gets full coverage from this class too.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class MySqlIntegrationTest {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.36")
            .withDatabaseName("auth_db")
            .withUsername("auth_service")
            .withPassword("test-password");

    @DynamicPropertySource
    static void overrideDatasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.datasource.driver-class-name", MYSQL::getDriverClassName);
    }

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void migrationsApplyAndTheCoreFlowWorksAgainstRealMySql() {
        String email = "mysql-flow-" + System.nanoTime() + "@example.com";

        ResponseEntity<UserResponse> registerResponse = restTemplate.postForEntity(
                "/api/v1/auth/register",
                new RegisterRequest(email, "correct-horse-battery", "Ada", "Lovelace"),
                UserResponse.class);
        assertThat(registerResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        ResponseEntity<TokenResponse> loginResponse = restTemplate.postForEntity(
                "/api/v1/auth/login", new LoginRequest(email, "correct-horse-battery"), TokenResponse.class);
        assertThat(loginResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(loginResponse.getBody().accessToken()).isNotBlank();
    }
}
