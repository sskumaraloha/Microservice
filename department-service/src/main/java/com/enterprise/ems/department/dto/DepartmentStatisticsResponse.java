package com.enterprise.ems.department.dto;

/**
 * {@code employeeHeadcount} is nullable by design: it is a live,
 * best-effort cross-service lookup against Employee Service (Step 8,
 * {@code EmployeeHeadcountClient}), not data this service owns. A
 * {@code null} means "Employee Service couldn't answer right now," never
 * "zero employees" - callers must not conflate the two.
 */
public record DepartmentStatisticsResponse(
        Long departmentId,
        int directChildrenCount,
        int totalDescendantCount,
        int depthFromRoot,
        Integer employeeHeadcount
) {
}
