package com.enterprise.ems.department.integration;

import com.enterprise.ems.department.dto.DepartmentRequest;
import com.enterprise.ems.department.dto.DepartmentResponse;
import com.enterprise.ems.department.dto.DepartmentStatisticsResponse;
import com.enterprise.ems.department.dto.DepartmentTreeNode;
import com.enterprise.ems.department.security.HeaderAuthenticationFilter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * See Employee Service's equivalent class (Step 6) for why every call
 * here goes through {@code exchange()} with explicit headers rather than
 * {@code TestRestTemplate}'s shorthand methods.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class DepartmentServiceApplicationIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void fullCrudLifecycleOverRealHttp() {
        DepartmentRequest request = new DepartmentRequest(uniqueName("Engineering"), uniqueCode("ENG"), "desc", null, null);

        DepartmentResponse created = exchange("/api/v1/departments", HttpMethod.POST, request, DepartmentResponse.class,
                HttpStatus.CREATED).getBody();
        Long id = created.id();

        DepartmentResponse fetched = exchange("/api/v1/departments/" + id, HttpMethod.GET, null, DepartmentResponse.class,
                HttpStatus.OK).getBody();
        assertThat(fetched.code()).isEqualTo(request.code());

        DepartmentRequest updateRequest = new DepartmentRequest(request.name(), request.code(), "updated desc", null, 42L);
        DepartmentResponse updated = exchange("/api/v1/departments/" + id, HttpMethod.PUT, updateRequest,
                DepartmentResponse.class, HttpStatus.OK).getBody();
        assertThat(updated.managerEmployeeId()).isEqualTo(42L);

        exchange("/api/v1/departments/" + id, HttpMethod.DELETE, null, Void.class, HttpStatus.NO_CONTENT);
        exchange("/api/v1/departments/" + id, HttpMethod.GET, null, String.class, HttpStatus.NOT_FOUND);
    }

    @Test
    void creatingADuplicateNameReturns409() {
        DepartmentRequest request = new DepartmentRequest(uniqueName("Sales"), uniqueCode("SALES"), null, null, null);

        exchange("/api/v1/departments", HttpMethod.POST, request, DepartmentResponse.class, HttpStatus.CREATED);
        DepartmentRequest duplicateName = new DepartmentRequest(request.name(), uniqueCode("SALES2"), null, null, null);
        exchange("/api/v1/departments", HttpMethod.POST, duplicateName, String.class, HttpStatus.CONFLICT);
    }

    @Test
    void hierarchyAncestorsAndStatisticsReflectARealTree() {
        Long rootId = exchange("/api/v1/departments", HttpMethod.POST,
                new DepartmentRequest(uniqueName("Root"), uniqueCode("ROOT"), null, null, null),
                DepartmentResponse.class, HttpStatus.CREATED).getBody().id();
        Long childId = exchange("/api/v1/departments", HttpMethod.POST,
                new DepartmentRequest(uniqueName("Child"), uniqueCode("CHILD"), null, rootId, null),
                DepartmentResponse.class, HttpStatus.CREATED).getBody().id();
        Long grandchildId = exchange("/api/v1/departments", HttpMethod.POST,
                new DepartmentRequest(uniqueName("Grandchild"), uniqueCode("GRAND"), null, childId, null),
                DepartmentResponse.class, HttpStatus.CREATED).getBody().id();

        DepartmentTreeNode tree = exchange("/api/v1/departments/" + rootId + "/hierarchy", HttpMethod.GET, null,
                DepartmentTreeNode.class, HttpStatus.OK).getBody();
        assertThat(tree.children()).hasSize(1);
        assertThat(tree.children().get(0).children()).hasSize(1);

        List<DepartmentResponse> ancestors = exchange("/api/v1/departments/" + grandchildId + "/ancestors",
                HttpMethod.GET, null, List.class, HttpStatus.OK).getBody();
        assertThat(ancestors).hasSize(2);

        DepartmentStatisticsResponse stats = exchange("/api/v1/departments/" + rootId + "/statistics", HttpMethod.GET,
                null, DepartmentStatisticsResponse.class, HttpStatus.OK).getBody();
        assertThat(stats.directChildrenCount()).isEqualTo(1);
        assertThat(stats.totalDescendantCount()).isEqualTo(2);
    }

    @Test
    void reparentingADepartmentUnderItsOwnDescendantIsRejected() {
        Long rootId = exchange("/api/v1/departments", HttpMethod.POST,
                new DepartmentRequest(uniqueName("Root"), uniqueCode("ROOT"), null, null, null),
                DepartmentResponse.class, HttpStatus.CREATED).getBody().id();
        Long childId = exchange("/api/v1/departments", HttpMethod.POST,
                new DepartmentRequest(uniqueName("Child"), uniqueCode("CHILD"), null, rootId, null),
                DepartmentResponse.class, HttpStatus.CREATED).getBody().id();

        DepartmentRequest reparentRootUnderChild = new DepartmentRequest(uniqueName("Root"), uniqueCode("ROOT2"), null, childId, null);
        exchange("/api/v1/departments/" + rootId, HttpMethod.PUT, reparentRootUnderChild, String.class, HttpStatus.BAD_REQUEST);
    }

    @Test
    void deletingADepartmentWithChildrenIsRejected() {
        Long rootId = exchange("/api/v1/departments", HttpMethod.POST,
                new DepartmentRequest(uniqueName("Root"), uniqueCode("ROOT"), null, null, null),
                DepartmentResponse.class, HttpStatus.CREATED).getBody().id();
        exchange("/api/v1/departments", HttpMethod.POST,
                new DepartmentRequest(uniqueName("Child"), uniqueCode("CHILD"), null, rootId, null),
                DepartmentResponse.class, HttpStatus.CREATED);

        exchange("/api/v1/departments/" + rootId, HttpMethod.DELETE, null, String.class, HttpStatus.CONFLICT);
    }

    @Test
    void readIsAllowedForAnyAuthenticatedRoleButWriteIsRestrictedToAdminOrManager() {
        HttpHeaders employeeRoleHeaders = new HttpHeaders();
        employeeRoleHeaders.add(HeaderAuthenticationFilter.USER_ID_HEADER, "1");
        employeeRoleHeaders.add(HeaderAuthenticationFilter.ROLES_HEADER, "EMPLOYEE");

        ResponseEntity<String> readResponse = restTemplate.exchange(
                "/api/v1/departments", HttpMethod.GET, new HttpEntity<>(employeeRoleHeaders), String.class);
        assertThat(readResponse.getStatusCode()).isEqualTo(HttpStatus.OK);

        DepartmentRequest request = new DepartmentRequest(uniqueName("Nobody"), uniqueCode("NOBODY"), null, null, null);
        ResponseEntity<String> writeResponse = restTemplate.exchange(
                "/api/v1/departments", HttpMethod.POST, new HttpEntity<>(request, employeeRoleHeaders), String.class);
        assertThat(writeResponse.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        ResponseEntity<String> unauthenticatedResponse = restTemplate.getForEntity("/api/v1/departments", String.class);
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

    private HttpHeaders adminHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.add(HeaderAuthenticationFilter.USER_ID_HEADER, "1");
        headers.add(HeaderAuthenticationFilter.ROLES_HEADER, "ADMIN");
        return headers;
    }

    private String uniqueName(String prefix) {
        return prefix + "-" + UUID.randomUUID();
    }

    private String uniqueCode(String prefix) {
        return (prefix + UUID.randomUUID().toString().substring(0, 8)).toUpperCase().replace("-", "");
    }
}
