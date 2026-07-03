package com.enterprise.ems.department.exception;

public class DepartmentNotFoundException extends RuntimeException {

    public DepartmentNotFoundException(Long id) {
        super("No department found with id '%d'".formatted(id));
    }
}
