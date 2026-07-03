package com.enterprise.ems.employee.exception;

/**
 * Distinct from {@link InvalidDepartmentException} on purpose: "the
 * department doesn't exist" (400, the caller's mistake) and "we couldn't
 * find out whether it exists" (503, our problem) are different failure
 * modes with different correct HTTP semantics — conflating them would
 * tell a client to fix a request that was never actually wrong.
 */
public class DepartmentServiceUnavailableException extends RuntimeException {

    public DepartmentServiceUnavailableException(Long departmentId, Throwable cause) {
        super("Could not verify department '%d': Department Service is unavailable".formatted(departmentId), cause);
    }
}
