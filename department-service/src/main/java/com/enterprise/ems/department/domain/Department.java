package com.enterprise.ems.department.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;

/**
 * {@code parentDepartmentId} is a plain column, not a JPA {@code @ManyToOne}
 * self-relationship — hierarchy traversal (subtree, ancestors) is done
 * explicitly in {@code DepartmentServiceImpl} via repository queries
 * instead of lazy-loaded object graph navigation, which keeps traversal
 * of an arbitrary-depth tree predictable (no surprise N+1 chains) and
 * keeps cycle-prevention logic easy to reason about and test in isolation.
 */
@Entity
@Table(name = "departments")
@Getter
@Setter
@NoArgsConstructor
public class Department {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String name;

    @Column(nullable = false, unique = true)
    private String code;

    private String description;

    @Column(name = "parent_department_id")
    private Long parentDepartmentId;

    @Column(name = "manager_employee_id")
    private Long managerEmployeeId;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private Instant updatedAt;
}
