package com.enterprise.ems.department.exception;

public class CircularHierarchyException extends RuntimeException {

    public CircularHierarchyException(Long departmentId, Long proposedParentId) {
        super("Setting department '%d' to have parent '%d' would create a cycle in the hierarchy"
                .formatted(departmentId, proposedParentId));
    }
}
