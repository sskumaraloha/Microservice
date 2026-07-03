package com.enterprise.ems.department.dto;

import java.util.List;

public record DepartmentTreeNode(Long id, String name, String code, List<DepartmentTreeNode> children) {
}
