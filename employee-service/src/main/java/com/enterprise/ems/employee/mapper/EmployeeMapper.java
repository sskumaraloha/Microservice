package com.enterprise.ems.employee.mapper;

import com.enterprise.ems.employee.domain.Employee;
import com.enterprise.ems.employee.domain.EmployeeDocument;
import com.enterprise.ems.employee.dto.EmployeeDocumentResponse;
import com.enterprise.ems.employee.dto.EmployeeRequest;
import com.enterprise.ems.employee.dto.EmployeeResponse;
import org.mapstruct.BeanMapping;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;
import org.mapstruct.NullValuePropertyMappingStrategy;

@Mapper(componentModel = "spring")
public interface EmployeeMapper {

    EmployeeResponse toResponse(Employee employee);

    Employee toEntity(EmployeeRequest request);

    @BeanMapping(nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE)
    void updateEntityFromRequest(EmployeeRequest request, @MappingTarget Employee employee);

    @Mapping(target = "employeeId", source = "employee.id")
    EmployeeDocumentResponse toDocumentResponse(EmployeeDocument document);
}
