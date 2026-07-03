package com.enterprise.ems.employee.exception;

public class EmployeeDocumentNotFoundException extends RuntimeException {

    public EmployeeDocumentNotFoundException(Long employeeId, Long documentId) {
        super("No document '%d' found for employee '%d'".formatted(documentId, employeeId));
    }
}
