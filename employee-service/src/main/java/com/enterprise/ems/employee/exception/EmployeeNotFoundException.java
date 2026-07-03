package com.enterprise.ems.employee.exception;

public class EmployeeNotFoundException extends RuntimeException {

    public EmployeeNotFoundException(Long id) {
        super("No employee found with id '%d'".formatted(id));
    }
}
