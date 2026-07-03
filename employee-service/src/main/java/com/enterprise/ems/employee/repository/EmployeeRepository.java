package com.enterprise.ems.employee.repository;

import com.enterprise.ems.employee.domain.Employee;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

/**
 * {@link JpaSpecificationExecutor} lets {@code EmployeeServiceImpl} compose
 * an arbitrary combination of search filters (name, email, department,
 * status) into a single dynamic query while still getting {@code Pageable}
 * pagination and sorting for free — the alternative would be a combinatorial
 * explosion of derived-query methods, one per filter combination.
 */
public interface EmployeeRepository extends JpaRepository<Employee, Long>, JpaSpecificationExecutor<Employee> {

    boolean existsByEmail(String email);

    boolean existsByEmailAndIdNot(String email, Long id);
}
