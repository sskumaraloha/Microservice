package com.enterprise.ems.department.controller;

import com.enterprise.ems.department.dto.DepartmentRequest;
import com.enterprise.ems.department.dto.DepartmentResponse;
import com.enterprise.ems.department.dto.DepartmentStatisticsResponse;
import com.enterprise.ems.department.dto.DepartmentTreeNode;
import com.enterprise.ems.department.service.DepartmentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "Departments")
@RestController
@RequestMapping("/api/v1/departments")
public class DepartmentController {

    private final DepartmentService departmentService;

    public DepartmentController(DepartmentService departmentService) {
        this.departmentService = departmentService;
    }

    @Operation(summary = "Create a department")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public DepartmentResponse create(@Valid @RequestBody DepartmentRequest request) {
        return departmentService.create(request);
    }

    @Operation(summary = "Get a department by id")
    @GetMapping("/{id}")
    public DepartmentResponse getById(@PathVariable Long id) {
        return departmentService.getById(id);
    }

    @Operation(summary = "List all departments, paginated")
    @GetMapping
    public Page<DepartmentResponse> list(Pageable pageable) {
        return departmentService.list(pageable);
    }

    @Operation(summary = "List a department's direct children")
    @GetMapping("/{id}/children")
    public List<DepartmentResponse> getChildren(@PathVariable Long id) {
        return departmentService.getChildren(id);
    }

    @Operation(summary = "Get the full subtree rooted at a department")
    @GetMapping("/{id}/hierarchy")
    public DepartmentTreeNode getHierarchy(@PathVariable Long id) {
        return departmentService.getHierarchy(id);
    }

    @Operation(summary = "Get the ancestor chain from the top-level department down to (not including) this one")
    @GetMapping("/{id}/ancestors")
    public List<DepartmentResponse> getAncestors(@PathVariable Long id) {
        return departmentService.getAncestors(id);
    }

    @Operation(summary = "Get local hierarchy statistics for a department")
    @GetMapping("/{id}/statistics")
    public DepartmentStatisticsResponse getStatistics(@PathVariable Long id) {
        return departmentService.getStatistics(id);
    }

    @Operation(summary = "Replace a department's editable fields")
    @PutMapping("/{id}")
    public DepartmentResponse update(@PathVariable Long id, @Valid @RequestBody DepartmentRequest request) {
        return departmentService.update(id, request);
    }

    @Operation(summary = "Delete a department (fails if it has sub-departments)")
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) {
        departmentService.delete(id);
    }
}
