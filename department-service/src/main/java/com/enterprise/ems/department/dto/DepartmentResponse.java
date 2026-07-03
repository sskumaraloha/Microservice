package com.enterprise.ems.department.dto;

public record DepartmentResponse(
        Long id,
        String name,
        String code,
        String description,
        Long parentDepartmentId,
        Long managerEmployeeId
) {
}
