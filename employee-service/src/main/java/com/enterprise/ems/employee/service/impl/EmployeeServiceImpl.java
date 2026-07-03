package com.enterprise.ems.employee.service.impl;

import com.enterprise.ems.employee.domain.Employee;
import com.enterprise.ems.employee.domain.EmployeeStatus;
import com.enterprise.ems.employee.dto.EmployeeRequest;
import com.enterprise.ems.employee.dto.EmployeeResponse;
import com.enterprise.ems.employee.dto.EmployeeSearchCriteria;
import com.enterprise.ems.employee.exception.DuplicateEmailException;
import com.enterprise.ems.employee.exception.EmployeeNotFoundException;
import com.enterprise.ems.employee.exception.InvalidDepartmentException;
import com.enterprise.ems.employee.mapper.EmployeeMapper;
import com.enterprise.ems.employee.repository.EmployeeRepository;
import com.enterprise.ems.employee.service.DepartmentValidationService;
import com.enterprise.ems.employee.service.EmployeeService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class EmployeeServiceImpl implements EmployeeService {

    private final EmployeeRepository employeeRepository;
    private final EmployeeMapper employeeMapper;
    private final DepartmentValidationService departmentValidationService;

    public EmployeeServiceImpl(EmployeeRepository employeeRepository,
                                EmployeeMapper employeeMapper,
                                DepartmentValidationService departmentValidationService) {
        this.employeeRepository = employeeRepository;
        this.employeeMapper = employeeMapper;
        this.departmentValidationService = departmentValidationService;
    }

    @Override
    @Transactional
    public EmployeeResponse create(EmployeeRequest request) {
        if (employeeRepository.existsByEmail(request.email())) {
            throw new DuplicateEmailException(request.email());
        }
        requireValidDepartment(request.departmentId());

        Employee employee = employeeMapper.toEntity(request);
        return employeeMapper.toResponse(employeeRepository.save(employee));
    }

    @Override
    public EmployeeResponse getById(Long id) {
        return employeeMapper.toResponse(findOrThrow(id));
    }

    @Override
    public Page<EmployeeResponse> search(EmployeeSearchCriteria criteria, Pageable pageable) {
        return employeeRepository.findAll(EmployeeSpecifications.matching(criteria), pageable)
                .map(employeeMapper::toResponse);
    }

    @Override
    public long count(EmployeeSearchCriteria criteria) {
        return employeeRepository.count(EmployeeSpecifications.matching(criteria));
    }

    @Override
    @Transactional
    public EmployeeResponse update(Long id, EmployeeRequest request) {
        Employee employee = findOrThrow(id);
        if (employeeRepository.existsByEmailAndIdNot(request.email(), id)) {
            throw new DuplicateEmailException(request.email());
        }
        requireValidDepartment(request.departmentId());

        employeeMapper.updateEntityFromRequest(request, employee);
        return employeeMapper.toResponse(employeeRepository.save(employee));
    }

    private void requireValidDepartment(Long departmentId) {
        if (!departmentValidationService.departmentExists(departmentId)) {
            throw new InvalidDepartmentException(departmentId);
        }
    }

    @Override
    @Transactional
    public EmployeeResponse updateStatus(Long id, EmployeeStatus status) {
        Employee employee = findOrThrow(id);
        employee.setStatus(status);
        return employeeMapper.toResponse(employeeRepository.save(employee));
    }

    @Override
    @Transactional
    public void delete(Long id) {
        if (!employeeRepository.existsById(id)) {
            throw new EmployeeNotFoundException(id);
        }
        employeeRepository.deleteById(id);
    }

    private Employee findOrThrow(Long id) {
        return employeeRepository.findById(id).orElseThrow(() -> new EmployeeNotFoundException(id));
    }
}
