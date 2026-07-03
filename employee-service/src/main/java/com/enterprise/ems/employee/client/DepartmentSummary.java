package com.enterprise.ems.employee.client;

/**
 * Deliberately minimal: Employee Service only needs to know a department
 * exists, not its full representation. Depending on the smallest contract
 * that satisfies the caller avoids Employee Service breaking every time
 * Department Service's response shape grows for reasons that have nothing
 * to do with this integration.
 */
public record DepartmentSummary(Long id, String name) {
}
