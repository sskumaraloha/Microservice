package com.enterprise.ems.employee.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

public record EmployeeRequest(
        @NotBlank @Size(max = 100) String firstName,
        @NotBlank @Size(max = 100) String lastName,
        @NotBlank @Email String email,
        @Pattern(regexp = "^$|^[+0-9 ()-]{7,30}$", message = "must be a valid phone number") String phone,
        @Size(max = 100) String position,
        @NotNull Long departmentId,
        @NotNull @PastOrPresent LocalDate hireDate
) {
}
