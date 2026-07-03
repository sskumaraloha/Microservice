package com.enterprise.ems.employee.dto;

import com.enterprise.ems.employee.domain.EmployeeStatus;

import java.time.LocalDate;

public record EmployeeResponse(
        Long id,
        String firstName,
        String lastName,
        String email,
        String phone,
        String position,
        Long departmentId,
        EmployeeStatus status,
        LocalDate hireDate
) {
}
