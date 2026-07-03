package com.enterprise.ems.employee.exception;

public class DuplicateEmailException extends RuntimeException {

    public DuplicateEmailException(String email) {
        super("An employee with email '%s' already exists".formatted(email));
    }
}
