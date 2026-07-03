package com.enterprise.ems.employee.service.impl;

import com.enterprise.ems.employee.domain.Employee;
import com.enterprise.ems.employee.dto.EmployeeSearchCriteria;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

final class EmployeeSpecifications {

    private EmployeeSpecifications() {
    }

    static Specification<Employee> matching(EmployeeSearchCriteria criteria) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();

            if (StringUtils.hasText(criteria.name())) {
                String pattern = "%" + criteria.name().toLowerCase() + "%";
                predicates.add(cb.or(
                        cb.like(cb.lower(root.get("firstName")), pattern),
                        cb.like(cb.lower(root.get("lastName")), pattern)));
            }
            if (StringUtils.hasText(criteria.email())) {
                predicates.add(cb.like(cb.lower(root.get("email")), "%" + criteria.email().toLowerCase() + "%"));
            }
            if (criteria.departmentId() != null) {
                predicates.add(cb.equal(root.get("departmentId"), criteria.departmentId()));
            }
            if (criteria.status() != null) {
                predicates.add(cb.equal(root.get("status"), criteria.status()));
            }

            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }
}
