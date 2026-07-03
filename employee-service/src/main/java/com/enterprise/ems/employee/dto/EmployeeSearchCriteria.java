package com.enterprise.ems.employee.dto;

import com.enterprise.ems.employee.domain.EmployeeStatus;

/**
 * Every field is optional — {@link com.enterprise.ems.employee.service.impl.EmployeeSpecifications}
 * only adds a predicate for the ones that are non-null/non-blank, so a
 * search with everything unset degenerates to "all employees."
 */
public record EmployeeSearchCriteria(String name, String email, Long departmentId, EmployeeStatus status) {
}
