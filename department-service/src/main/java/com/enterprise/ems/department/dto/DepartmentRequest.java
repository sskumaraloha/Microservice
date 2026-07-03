package com.enterprise.ems.department.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record DepartmentRequest(
        @NotBlank @Size(max = 150) String name,
        @NotBlank @Pattern(regexp = "^[A-Z0-9_-]{2,30}$", message = "must be 2-30 uppercase letters, digits, '-' or '_'") String code,
        @Size(max = 500) String description,
        Long parentDepartmentId,
        Long managerEmployeeId
) {
}
