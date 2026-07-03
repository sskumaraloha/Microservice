package com.enterprise.ems.employee.integration;

import com.enterprise.ems.employee.domain.EmployeeStatus;
import com.enterprise.ems.employee.dto.EmployeeRequest;
import com.enterprise.ems.employee.dto.EmployeeResponse;
import com.enterprise.ems.employee.dto.UpdateStatusRequest;
import com.enterprise.ems.employee.security.HeaderAuthenticationFilter;
import com.enterprise.ems.employee.service.DepartmentValidationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link TestRestTemplate}'s shorthand methods (postForEntity, put,
 * delete...) don't accept custom headers, so every request here goes
 * through {@code exchange()} with explicit trusted-gateway-header
 * simulation (see {@link #adminHeaders()}) — otherwise every call would
 * silently hit the security filter chain unauthenticated.
 *
 * <p>{@link DepartmentValidationService} is mocked here (always
 * "exists") because this class tests Employee CRUD, not the Department
 * Service integration itself — that gets its own focused test,
 * {@code client.DepartmentValidationServiceWireMockTest}, against a real
 * stubbed HTTP server so the Feign/Resilience4j wiring is exercised for
 * real rather than mocked away twice.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class EmployeeServiceApplicationIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @MockBean
    private DepartmentValidationService departmentValidationService;

    @BeforeEach
    void stubDepartmentAlwaysExists() {
        org.mockito.Mockito.when(departmentValidationService.departmentExists(org.mockito.ArgumentMatchers.any()))
                .thenReturn(true);
    }

    @Test
    void fullCrudLifecycleOverRealHttp() {
        EmployeeRequest request = new EmployeeRequest("Grace", "Hopper", uniqueEmail(), "+1 555 0100", "Engineer",
                1L, LocalDate.of(2021, 3, 1));

        EmployeeResponse created = exchange("/api/v1/employees", HttpMethod.POST, request, EmployeeResponse.class,
                HttpStatus.CREATED).getBody();
        Long id = created.id();

        EmployeeResponse fetched = exchange("/api/v1/employees/" + id, HttpMethod.GET, null, EmployeeResponse.class,
                HttpStatus.OK).getBody();
        assertThat(fetched.lastName()).isEqualTo("Hopper");

        EmployeeRequest updateRequest = new EmployeeRequest("Grace", "Hopper-Murray", request.email(), "+1 555 0101",
                "Senior Engineer", 2L, request.hireDate());
        exchange("/api/v1/employees/" + id, HttpMethod.PUT, updateRequest, EmployeeResponse.class, HttpStatus.OK);

        EmployeeResponse afterUpdate = exchange("/api/v1/employees/" + id, HttpMethod.GET, null, EmployeeResponse.class,
                HttpStatus.OK).getBody();
        assertThat(afterUpdate.lastName()).isEqualTo("Hopper-Murray");
        assertThat(afterUpdate.departmentId()).isEqualTo(2L);

        exchange("/api/v1/employees/" + id + "/status", HttpMethod.PATCH,
                new UpdateStatusRequest(EmployeeStatus.ON_LEAVE), EmployeeResponse.class, HttpStatus.OK);
        EmployeeResponse afterStatusChange = exchange("/api/v1/employees/" + id, HttpMethod.GET, null,
                EmployeeResponse.class, HttpStatus.OK).getBody();
        assertThat(afterStatusChange.status()).isEqualTo(EmployeeStatus.ON_LEAVE);

        exchange("/api/v1/employees/" + id, HttpMethod.DELETE, null, Void.class, HttpStatus.NO_CONTENT);
        exchange("/api/v1/employees/" + id, HttpMethod.GET, null, String.class, HttpStatus.NOT_FOUND);
    }

    @Test
    void creatingTheSameEmailTwiceReturns409() {
        EmployeeRequest request = new EmployeeRequest("Grace", "Hopper", uniqueEmail(), null, null, 1L,
                LocalDate.of(2021, 3, 1));

        exchange("/api/v1/employees", HttpMethod.POST, request, EmployeeResponse.class, HttpStatus.CREATED);
        exchange("/api/v1/employees", HttpMethod.POST, request, String.class, HttpStatus.CONFLICT);
    }

    @Test
    void searchSupportsFilteringPaginationAndSorting() {
        long departmentId = System.nanoTime(); // unique per run so the totalElements assertion below is exact
        for (String[] name : new String[][]{{"Alan", "Turing"}, {"Barbara", "Liskov"}, {"Carl", "Hewitt"}}) {
            exchange("/api/v1/employees", HttpMethod.POST,
                    new EmployeeRequest(name[0], name[1], uniqueEmail(), null, null, departmentId, LocalDate.of(2019, 6, 1)),
                    EmployeeResponse.class, HttpStatus.CREATED);
        }

        ResponseEntity<Map<String, Object>> page = restTemplate.exchange(
                "/api/v1/employees?departmentId=" + departmentId + "&size=2&sort=lastName,asc",
                HttpMethod.GET, new HttpEntity<>(adminHeaders()), new ParameterizedTypeReference<>() {
                });

        assertThat(page.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat((Integer) page.getBody().get("totalElements")).isEqualTo(3);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> content = (List<Map<String, Object>>) page.getBody().get("content");
        assertThat(content).hasSize(2);
        assertThat(content.get(0).get("lastName")).isEqualTo("Hewitt"); // alphabetically first
    }

    @Test
    void uploadListDownloadAndDeleteADocument() {
        EmployeeResponse created = exchange("/api/v1/employees", HttpMethod.POST,
                new EmployeeRequest("Ada", "Lovelace", uniqueEmail(), null, null, 1L, LocalDate.of(2020, 1, 1)),
                EmployeeResponse.class, HttpStatus.CREATED).getBody();
        Long employeeId = created.id();

        ResponseEntity<Map> uploadResponse = uploadFile(employeeId, "resume.pdf", MediaType.APPLICATION_PDF,
                "resume contents".getBytes(), Map.class);
        assertThat(uploadResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        ResponseEntity<List> listResponse = restTemplate.exchange(
                "/api/v1/employees/" + employeeId + "/documents", HttpMethod.GET,
                new HttpEntity<>(adminHeaders()), List.class);
        assertThat(listResponse.getBody()).hasSize(1);
        Long documentId = ((Number) ((Map<?, ?>) listResponse.getBody().get(0)).get("id")).longValue();

        ResponseEntity<byte[]> downloadResponse = restTemplate.exchange(
                "/api/v1/employees/" + employeeId + "/documents/" + documentId + "/download", HttpMethod.GET,
                new HttpEntity<>(adminHeaders()), byte[].class);
        assertThat(downloadResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(new String(downloadResponse.getBody())).isEqualTo("resume contents");

        exchange("/api/v1/employees/" + employeeId + "/documents/" + documentId, HttpMethod.DELETE, null, Void.class,
                HttpStatus.NO_CONTENT);
        ResponseEntity<List> afterDelete = restTemplate.exchange(
                "/api/v1/employees/" + employeeId + "/documents", HttpMethod.GET,
                new HttpEntity<>(adminHeaders()), List.class);
        assertThat(afterDelete.getBody()).isEmpty();
    }

    @Test
    void uploadRejectsADisallowedContentType() {
        EmployeeResponse created = exchange("/api/v1/employees", HttpMethod.POST,
                new EmployeeRequest("Ada", "Lovelace", uniqueEmail(), null, null, 1L, LocalDate.of(2020, 1, 1)),
                EmployeeResponse.class, HttpStatus.CREATED).getBody();

        ResponseEntity<String> response = uploadFile(created.id(), "virus.exe",
                MediaType.valueOf("application/x-msdownload"), "MZ".getBytes(), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void readIsAllowedForAnyAuthenticatedRoleButWriteIsRestrictedToAdminOrManager() {
        HttpHeaders employeeRoleHeaders = new HttpHeaders();
        employeeRoleHeaders.add(HeaderAuthenticationFilter.USER_ID_HEADER, "1");
        employeeRoleHeaders.add(HeaderAuthenticationFilter.ROLES_HEADER, "EMPLOYEE");

        ResponseEntity<String> readResponse = restTemplate.exchange(
                "/api/v1/employees", HttpMethod.GET, new HttpEntity<>(employeeRoleHeaders), String.class);
        assertThat(readResponse.getStatusCode()).isEqualTo(HttpStatus.OK);

        EmployeeRequest request = new EmployeeRequest("Nobody", "Denied", uniqueEmail(), null, null, 1L,
                LocalDate.of(2020, 1, 1));
        ResponseEntity<String> writeResponse = restTemplate.exchange(
                "/api/v1/employees", HttpMethod.POST, new HttpEntity<>(request, employeeRoleHeaders), String.class);
        assertThat(writeResponse.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        ResponseEntity<String> unauthenticatedResponse = restTemplate.getForEntity("/api/v1/employees", String.class);
        assertThat(unauthenticatedResponse.getStatusCode()).isIn(HttpStatus.UNAUTHORIZED, HttpStatus.FORBIDDEN);
    }

    private <T> ResponseEntity<T> exchange(String url, HttpMethod method, Object body, Class<T> responseType,
                                            HttpStatus expectedStatus) {
        HttpHeaders headers = adminHeaders();
        if (body != null) {
            headers.setContentType(MediaType.APPLICATION_JSON);
        }
        ResponseEntity<T> response = restTemplate.exchange(url, method, new HttpEntity<>(body, headers), responseType);
        assertThat(response.getStatusCode()).isEqualTo(expectedStatus);
        return response;
    }

    private <T> ResponseEntity<T> uploadFile(Long employeeId, String filename, MediaType contentType, byte[] bytes,
                                              Class<T> responseType) {
        HttpHeaders filePartHeaders = new HttpHeaders();
        filePartHeaders.setContentType(contentType);

        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new HttpEntity<>(new ByteArrayResource(bytes) {
            @Override
            public String getFilename() {
                return filename;
            }
        }, filePartHeaders));

        HttpHeaders requestHeaders = adminHeaders();
        requestHeaders.setContentType(MediaType.MULTIPART_FORM_DATA);

        return restTemplate.exchange(
                "/api/v1/employees/" + employeeId + "/documents?documentType=RESUME", HttpMethod.POST,
                new HttpEntity<>(body, requestHeaders), responseType);
    }

    private HttpHeaders adminHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.add(HeaderAuthenticationFilter.USER_ID_HEADER, "1");
        headers.add(HeaderAuthenticationFilter.ROLES_HEADER, "ADMIN");
        return headers;
    }

    private String uniqueEmail() {
        return "employee-" + UUID.randomUUID() + "@example.com";
    }
}
