package com.enterprise.ems.department.integration;

import com.enterprise.ems.department.dto.DepartmentRequest;
import com.enterprise.ems.department.dto.DepartmentResponse;
import com.enterprise.ems.department.security.HeaderAuthenticationFilter;
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
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Re-validates the Flyway migration (including the self-referencing
 * foreign key) against a real MySQL. Self-skips without a Docker daemon.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class MySqlIntegrationTest {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.36")
            .withDatabaseName("department_db")
            .withUsername("department_service")
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
    void migrationsApplyAndBasicCreateReadWorkAgainstRealMySql() {
        HttpHeaders headers = new HttpHeaders();
        headers.add(HeaderAuthenticationFilter.USER_ID_HEADER, "1");
        headers.add(HeaderAuthenticationFilter.ROLES_HEADER, "ADMIN");

        DepartmentRequest request = new DepartmentRequest(
                "Engineering-" + UUID.randomUUID(), "ENG" + System.nanoTime() % 100000, null, null, null);

        ResponseEntity<DepartmentResponse> created = restTemplate.exchange(
                "/api/v1/departments", HttpMethod.POST, new HttpEntity<>(request, headers), DepartmentResponse.class);

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(created.getBody().code()).isEqualTo(request.code());
    }
}
