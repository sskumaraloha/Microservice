package com.enterprise.ems.department.service.impl;

import com.enterprise.ems.department.client.EmployeeHeadcountClient;
import com.enterprise.ems.department.domain.Department;
import com.enterprise.ems.department.dto.DepartmentRequest;
import com.enterprise.ems.department.dto.DepartmentResponse;
import com.enterprise.ems.department.dto.DepartmentStatisticsResponse;
import com.enterprise.ems.department.dto.DepartmentTreeNode;
import com.enterprise.ems.department.exception.CircularHierarchyException;
import com.enterprise.ems.department.exception.DepartmentHasChildrenException;
import com.enterprise.ems.department.exception.DepartmentNotFoundException;
import com.enterprise.ems.department.exception.DuplicateDepartmentException;
import com.enterprise.ems.department.mapper.DepartmentMapper;
import com.enterprise.ems.department.repository.DepartmentRepository;
import com.enterprise.ems.department.service.DepartmentService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
@Transactional(readOnly = true)
public class DepartmentServiceImpl implements DepartmentService {

    private final DepartmentRepository departmentRepository;
    private final DepartmentMapper departmentMapper;
    private final EmployeeHeadcountClient employeeHeadcountClient;

    public DepartmentServiceImpl(DepartmentRepository departmentRepository,
                                  DepartmentMapper departmentMapper,
                                  EmployeeHeadcountClient employeeHeadcountClient) {
        this.departmentRepository = departmentRepository;
        this.departmentMapper = departmentMapper;
        this.employeeHeadcountClient = employeeHeadcountClient;
    }

    @Override
    @Transactional
    public DepartmentResponse create(DepartmentRequest request) {
        validateUniqueNameAndCode(request, null);
        if (request.parentDepartmentId() != null) {
            requireExists(request.parentDepartmentId());
        }

        Department department = departmentMapper.toEntity(request);
        return departmentMapper.toResponse(departmentRepository.save(department));
    }

    @Override
    public DepartmentResponse getById(Long id) {
        return departmentMapper.toResponse(findOrThrow(id));
    }

    @Override
    public Page<DepartmentResponse> list(Pageable pageable) {
        return departmentRepository.findAll(pageable).map(departmentMapper::toResponse);
    }

    @Override
    public List<DepartmentResponse> getChildren(Long parentId) {
        requireExists(parentId);
        return departmentRepository.findByParentDepartmentId(parentId).stream()
                .map(departmentMapper::toResponse)
                .toList();
    }

    @Override
    @Transactional
    public DepartmentResponse update(Long id, DepartmentRequest request) {
        Department department = findOrThrow(id);
        validateUniqueNameAndCode(request, id);

        if (request.parentDepartmentId() != null) {
            requireExists(request.parentDepartmentId());
            validateNoCycle(id, request.parentDepartmentId());
        }

        departmentMapper.updateEntityFromRequest(request, department);
        return departmentMapper.toResponse(departmentRepository.save(department));
    }

    @Override
    @Transactional
    public void delete(Long id) {
        requireExists(id);
        if (departmentRepository.countByParentDepartmentId(id) > 0) {
            throw new DepartmentHasChildrenException(id);
        }
        departmentRepository.deleteById(id);
    }

    @Override
    public DepartmentTreeNode getHierarchy(Long id) {
        Department root = findOrThrow(id);
        return buildTree(root);
    }

    @Override
    public List<DepartmentResponse> getAncestors(Long id) {
        Department department = findOrThrow(id);
        List<Department> ancestors = new ArrayList<>();

        Long currentParentId = department.getParentDepartmentId();
        Set<Long> visited = new HashSet<>();
        while (currentParentId != null && visited.add(currentParentId)) {
            Department parent = findOrThrow(currentParentId);
            ancestors.add(parent);
            currentParentId = parent.getParentDepartmentId();
        }

        Collections.reverse(ancestors);
        return ancestors.stream().map(departmentMapper::toResponse).toList();
    }

    @Override
    public DepartmentStatisticsResponse getStatistics(Long id) {
        requireExists(id);
        int directChildren = (int) departmentRepository.countByParentDepartmentId(id);
        int totalDescendants = countDescendants(id);
        int depth = getAncestors(id).size();
        Integer headcount = employeeHeadcountClient.getHeadcount(id);
        return new DepartmentStatisticsResponse(id, directChildren, totalDescendants, depth, headcount);
    }

    private DepartmentTreeNode buildTree(Department department) {
        List<DepartmentTreeNode> children = departmentRepository.findByParentDepartmentId(department.getId()).stream()
                .map(this::buildTree)
                .toList();
        return new DepartmentTreeNode(department.getId(), department.getName(), department.getCode(), children);
    }

    private int countDescendants(Long id) {
        List<Department> children = departmentRepository.findByParentDepartmentId(id);
        int count = children.size();
        for (Department child : children) {
            count += countDescendants(child.getId());
        }
        return count;
    }

    /**
     * Walks from {@code proposedParentId} up to the root. If {@code departmentId}
     * appears anywhere in that chain, {@code departmentId} is an ancestor of
     * {@code proposedParentId} — meaning {@code proposedParentId} is inside
     * {@code departmentId}'s own subtree, and making it the parent would
     * create a cycle.
     */
    private void validateNoCycle(Long departmentId, Long proposedParentId) {
        if (proposedParentId.equals(departmentId)) {
            throw new CircularHierarchyException(departmentId, proposedParentId);
        }

        Long current = proposedParentId;
        Set<Long> visited = new HashSet<>();
        while (current != null) {
            if (current.equals(departmentId)) {
                throw new CircularHierarchyException(departmentId, proposedParentId);
            }
            if (!visited.add(current)) {
                break; // defensive: a pre-existing cycle should be impossible, but never loop forever
            }
            current = departmentRepository.findById(current).map(Department::getParentDepartmentId).orElse(null);
        }
    }

    private void validateUniqueNameAndCode(DepartmentRequest request, Long excludingId) {
        boolean nameTaken = excludingId == null
                ? departmentRepository.existsByName(request.name())
                : departmentRepository.existsByNameAndIdNot(request.name(), excludingId);
        if (nameTaken) {
            throw new DuplicateDepartmentException("A department named '%s' already exists".formatted(request.name()));
        }

        boolean codeTaken = excludingId == null
                ? departmentRepository.existsByCode(request.code())
                : departmentRepository.existsByCodeAndIdNot(request.code(), excludingId);
        if (codeTaken) {
            throw new DuplicateDepartmentException("A department with code '%s' already exists".formatted(request.code()));
        }
    }

    private void requireExists(Long id) {
        if (!departmentRepository.existsById(id)) {
            throw new DepartmentNotFoundException(id);
        }
    }

    private Department findOrThrow(Long id) {
        return departmentRepository.findById(id).orElseThrow(() -> new DepartmentNotFoundException(id));
    }
}
