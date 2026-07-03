package com.enterprise.ems.department.mapper;

import com.enterprise.ems.department.domain.Department;
import com.enterprise.ems.department.dto.DepartmentRequest;
import com.enterprise.ems.department.dto.DepartmentResponse;
import org.mapstruct.Mapper;
import org.mapstruct.MappingTarget;

@Mapper(componentModel = "spring")
public interface DepartmentMapper {

    DepartmentResponse toResponse(Department department);

    Department toEntity(DepartmentRequest request);

    /**
     * Unlike Employee Service's equivalent method, this one does NOT
     * ignore null source values: {@code parentDepartmentId} and
     * {@code managerEmployeeId} are legitimately nullable (a root
     * department has no parent; a department can have no manager yet),
     * and PUT is a full-replace operation — a client omitting one of
     * these fields must be able to clear it, not have the old value
     * silently preserved.
     */
    void updateEntityFromRequest(DepartmentRequest request, @MappingTarget Department department);
}
