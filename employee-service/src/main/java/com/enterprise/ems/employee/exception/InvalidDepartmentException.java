package com.enterprise.ems.employee.exception;

public class InvalidDepartmentException extends RuntimeException {

    public InvalidDepartmentException(Long departmentId) {
        super("No department found with id '%d'".formatted(departmentId));
    }
}
