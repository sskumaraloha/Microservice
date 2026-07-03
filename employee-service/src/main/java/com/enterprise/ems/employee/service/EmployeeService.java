package com.enterprise.ems.employee.service;

import com.enterprise.ems.employee.domain.EmployeeStatus;
import com.enterprise.ems.employee.dto.EmployeeRequest;
import com.enterprise.ems.employee.dto.EmployeeResponse;
import com.enterprise.ems.employee.dto.EmployeeSearchCriteria;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface EmployeeService {

    EmployeeResponse create(EmployeeRequest request);

    EmployeeResponse getById(Long id);

    Page<EmployeeResponse> search(EmployeeSearchCriteria criteria, Pageable pageable);

    /** Same filters as {@link #search}, but only the count — backs Department Service's headcount statistic (Step 8). */
    long count(EmployeeSearchCriteria criteria);

    EmployeeResponse update(Long id, EmployeeRequest request);

    EmployeeResponse updateStatus(Long id, EmployeeStatus status);

    void delete(Long id);
}
