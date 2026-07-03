package com.enterprise.ems.employee.integration;

import com.enterprise.ems.employee.dto.EmployeeRequest;
import com.enterprise.ems.employee.dto.EmployeeResponse;
import com.enterprise.ems.employee.security.HeaderAuthenticationFilter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Re-validates the Flyway migrations and a basic create/read against a
 * real MySQL, the same rationale as Auth Service's class of the same
 * name (Step 5). Self-skips without a Docker daemon.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class MySqlIntegrationTest {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.36")
            .withDatabaseName("employee_db")
            .withUsername("employee_service")
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

        EmployeeRequest request = new EmployeeRequest("Grace", "Hopper", "grace-" + System.nanoTime() + "@example.com",
                null, null, 1L, LocalDate.of(2021, 3, 1));

        ResponseEntity<EmployeeResponse> created = restTemplate.exchange(
                "/api/v1/employees", org.springframework.http.HttpMethod.POST,
                new HttpEntity<>(request, headers), EmployeeResponse.class);

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(created.getBody().lastName()).isEqualTo("Hopper");
    }
}
