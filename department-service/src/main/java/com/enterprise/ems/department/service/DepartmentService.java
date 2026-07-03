package com.enterprise.ems.department.service;

import com.enterprise.ems.department.dto.DepartmentRequest;
import com.enterprise.ems.department.dto.DepartmentResponse;
import com.enterprise.ems.department.dto.DepartmentStatisticsResponse;
import com.enterprise.ems.department.dto.DepartmentTreeNode;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;

public interface DepartmentService {

    DepartmentResponse create(DepartmentRequest request);

    DepartmentResponse getById(Long id);

    Page<DepartmentResponse> list(Pageable pageable);

    List<DepartmentResponse> getChildren(Long parentId);

    DepartmentResponse update(Long id, DepartmentRequest request);

    void delete(Long id);

    /** The full subtree rooted at {@code id}, including {@code id} itself as the root node. */
    DepartmentTreeNode getHierarchy(Long id);

    /** Ordered from the top-level ancestor down to (but not including) {@code id} itself. */
    List<DepartmentResponse> getAncestors(Long id);

    DepartmentStatisticsResponse getStatistics(Long id);
}
