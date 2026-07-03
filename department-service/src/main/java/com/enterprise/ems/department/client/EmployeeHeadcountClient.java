package com.enterprise.ems.department.client;

/**
 * Headcount is supplementary statistics, not a business rule Department
 * Service enforces - unlike Employee Service's department-existence check
 * (Step 8, {@code DepartmentValidationService}), which throws when
 * Employee Service can't be reached because creating an employee against
 * an unverifiable department is unsafe to allow. Reporting "unknown"
 * headcount when Employee Service is briefly down is not: the statistics
 * endpoint should degrade gracefully instead of failing entirely, so this
 * returns {@code null} rather than throwing.
 */
public interface EmployeeHeadcountClient {
    Integer getHeadcount(Long departmentId);
}
