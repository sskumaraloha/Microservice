package com.enterprise.ems.employee.dto;

import com.enterprise.ems.employee.domain.EmployeeStatus;
import jakarta.validation.constraints.NotNull;

public record UpdateStatusRequest(@NotNull EmployeeStatus status) {
}
