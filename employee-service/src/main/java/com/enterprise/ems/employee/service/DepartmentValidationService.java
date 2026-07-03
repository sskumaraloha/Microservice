package com.enterprise.ems.employee.service;

public interface DepartmentValidationService {

    /**
     * @throws com.enterprise.ems.employee.exception.DepartmentServiceUnavailableException
     *         if Department Service cannot be reached after retries, or its circuit breaker is open
     */
    boolean departmentExists(Long departmentId);
}
