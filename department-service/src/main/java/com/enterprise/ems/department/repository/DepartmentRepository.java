package com.enterprise.ems.department.repository;

import com.enterprise.ems.department.domain.Department;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DepartmentRepository extends JpaRepository<Department, Long> {

    boolean existsByName(String name);

    boolean existsByNameAndIdNot(String name, Long id);

    boolean existsByCode(String code);

    boolean existsByCodeAndIdNot(String code, Long id);

    List<Department> findByParentDepartmentId(Long parentDepartmentId);

    List<Department> findByParentDepartmentIdIsNull();

    long countByParentDepartmentId(Long parentDepartmentId);
}
