package com.enterprise.ems.department.exception;

public class DepartmentHasChildrenException extends RuntimeException {

    public DepartmentHasChildrenException(Long id) {
        super("Department '%d' has one or more sub-departments and cannot be deleted".formatted(id));
    }
}
