package com.enterprise.ems.department.service;

import com.enterprise.ems.department.client.EmployeeHeadcountClient;
import com.enterprise.ems.department.domain.Department;
import com.enterprise.ems.department.dto.DepartmentRequest;
import com.enterprise.ems.department.dto.DepartmentResponse;
import com.enterprise.ems.department.dto.DepartmentTreeNode;
import com.enterprise.ems.department.exception.CircularHierarchyException;
import com.enterprise.ems.department.exception.DepartmentHasChildrenException;
import com.enterprise.ems.department.exception.DepartmentNotFoundException;
import com.enterprise.ems.department.exception.DuplicateDepartmentException;
import com.enterprise.ems.department.mapper.DepartmentMapper;
import com.enterprise.ems.department.mapper.DepartmentMapperImpl;
import com.enterprise.ems.department.repository.DepartmentRepository;
import com.enterprise.ems.department.service.impl.DepartmentServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DepartmentServiceImplTest {

    private DepartmentRepository departmentRepository;
    private final DepartmentMapper departmentMapper = new DepartmentMapperImpl();
    private EmployeeHeadcountClient employeeHeadcountClient;
    private DepartmentService departmentService;

    @BeforeEach
    void setUp() {
        departmentRepository = mock(DepartmentRepository.class);
        employeeHeadcountClient = mock(EmployeeHeadcountClient.class);
        departmentService = new DepartmentServiceImpl(departmentRepository, departmentMapper, employeeHeadcountClient);
        when(departmentRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    private DepartmentRequest request(String name, String code, Long parentId) {
        return new DepartmentRequest(name, code, "desc", parentId, null);
    }

    private Department department(Long id, Long parentId) {
        Department department = new Department();
        department.setId(id);
        department.setName("Dept " + id);
        department.setCode("D" + id);
        department.setParentDepartmentId(parentId);
        return department;
    }

    @Test
    void createSavesWhenNameAndCodeAreFree() {
        when(departmentRepository.existsByName("Engineering")).thenReturn(false);
        when(departmentRepository.existsByCode("ENG")).thenReturn(false);

        DepartmentResponse response = departmentService.create(request("Engineering", "ENG", null));

        assertThat(response.name()).isEqualTo("Engineering");
        verify(departmentRepository).save(any(Department.class));
    }

    @Test
    void createRejectsADuplicateName() {
        when(departmentRepository.existsByName("Engineering")).thenReturn(true);

        assertThatThrownBy(() -> departmentService.create(request("Engineering", "ENG", null)))
                .isInstanceOf(DuplicateDepartmentException.class);
        verify(departmentRepository, never()).save(any());
    }

    @Test
    void createRejectsADuplicateCode() {
        when(departmentRepository.existsByName("Engineering")).thenReturn(false);
        when(departmentRepository.existsByCode("ENG")).thenReturn(true);

        assertThatThrownBy(() -> departmentService.create(request("Engineering", "ENG", null)))
                .isInstanceOf(DuplicateDepartmentException.class);
    }

    @Test
    void createRejectsANonExistentParent() {
        when(departmentRepository.existsByName(any())).thenReturn(false);
        when(departmentRepository.existsByCode(any())).thenReturn(false);
        when(departmentRepository.existsById(99L)).thenReturn(false);

        assertThatThrownBy(() -> departmentService.create(request("Engineering", "ENG", 99L)))
                .isInstanceOf(DepartmentNotFoundException.class);
    }

    @Test
    void getByIdThrowsWhenMissing() {
        when(departmentRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> departmentService.getById(99L)).isInstanceOf(DepartmentNotFoundException.class);
    }

    @Test
    void updateRejectsSettingADepartmentAsItsOwnParent() {
        Department department = department(1L, null);
        when(departmentRepository.findById(1L)).thenReturn(Optional.of(department));
        when(departmentRepository.existsById(1L)).thenReturn(true);

        assertThatThrownBy(() -> departmentService.update(1L, request("Engineering", "ENG", 1L)))
                .isInstanceOf(CircularHierarchyException.class);
    }

    @Test
    void updateRejectsSettingADepartmentsChildAsItsParent() {
        // 1 (root) -> 2 (child of 1). Reparenting 1 under 2 would create a cycle.
        Department root = department(1L, null);
        Department child = department(2L, 1L);
        when(departmentRepository.findById(1L)).thenReturn(Optional.of(root));
        when(departmentRepository.findById(2L)).thenReturn(Optional.of(child));
        when(departmentRepository.existsById(2L)).thenReturn(true);

        assertThatThrownBy(() -> departmentService.update(1L, request("Root", "R1", 2L)))
                .isInstanceOf(CircularHierarchyException.class);
    }

    @Test
    void updateRejectsSettingAGrandchildAsParent() {
        // 1 -> 2 -> 3. Reparenting 1 under 3 must also be rejected.
        Department dept1 = department(1L, null);
        Department dept2 = department(2L, 1L);
        Department dept3 = department(3L, 2L);
        when(departmentRepository.findById(1L)).thenReturn(Optional.of(dept1));
        when(departmentRepository.findById(2L)).thenReturn(Optional.of(dept2));
        when(departmentRepository.findById(3L)).thenReturn(Optional.of(dept3));
        when(departmentRepository.existsById(3L)).thenReturn(true);

        assertThatThrownBy(() -> departmentService.update(1L, request("Root", "R1", 3L)))
                .isInstanceOf(CircularHierarchyException.class);
    }

    @Test
    void updateAllowsAValidReparenting() {
        Department dept1 = department(1L, null);
        Department dept2 = department(2L, null); // unrelated department
        when(departmentRepository.findById(1L)).thenReturn(Optional.of(dept1));
        when(departmentRepository.findById(2L)).thenReturn(Optional.of(dept2));
        when(departmentRepository.existsById(2L)).thenReturn(true);
        when(departmentRepository.existsByNameAndIdNot(any(), eq(1L))).thenReturn(false);
        when(departmentRepository.existsByCodeAndIdNot(any(), eq(1L))).thenReturn(false);

        DepartmentResponse response = departmentService.update(1L, request("Team A", "TA", 2L));

        assertThat(response.parentDepartmentId()).isEqualTo(2L);
    }

    @Test
    void deleteThrowsWhenTheDepartmentHasChildren() {
        when(departmentRepository.existsById(1L)).thenReturn(true);
        when(departmentRepository.countByParentDepartmentId(1L)).thenReturn(2L);

        assertThatThrownBy(() -> departmentService.delete(1L)).isInstanceOf(DepartmentHasChildrenException.class);
        verify(departmentRepository, never()).deleteById(any());
    }

    @Test
    void deleteSucceedsWhenTheDepartmentHasNoChildren() {
        when(departmentRepository.existsById(1L)).thenReturn(true);
        when(departmentRepository.countByParentDepartmentId(1L)).thenReturn(0L);

        departmentService.delete(1L);

        verify(departmentRepository).deleteById(1L);
    }

    @Test
    void getHierarchyBuildsTheFullSubtree() {
        Department root = department(1L, null);
        Department childA = department(2L, 1L);
        Department childB = department(3L, 1L);
        Department grandchild = department(4L, 2L);
        when(departmentRepository.findById(1L)).thenReturn(Optional.of(root));
        when(departmentRepository.findByParentDepartmentId(1L)).thenReturn(List.of(childA, childB));
        when(departmentRepository.findByParentDepartmentId(2L)).thenReturn(List.of(grandchild));
        when(departmentRepository.findByParentDepartmentId(3L)).thenReturn(List.of());
        when(departmentRepository.findByParentDepartmentId(4L)).thenReturn(List.of());

        DepartmentTreeNode tree = departmentService.getHierarchy(1L);

        assertThat(tree.id()).isEqualTo(1L);
        assertThat(tree.children()).hasSize(2);
        DepartmentTreeNode childANode = tree.children().stream().filter(n -> n.id().equals(2L)).findFirst().orElseThrow();
        assertThat(childANode.children()).hasSize(1);
        assertThat(childANode.children().get(0).id()).isEqualTo(4L);
    }

    @Test
    void getAncestorsReturnsThemOrderedFromTheRootDown() {
        Department root = department(1L, null);
        Department mid = department(2L, 1L);
        Department leaf = department(3L, 2L);
        when(departmentRepository.findById(3L)).thenReturn(Optional.of(leaf));
        when(departmentRepository.findById(2L)).thenReturn(Optional.of(mid));
        when(departmentRepository.findById(1L)).thenReturn(Optional.of(root));

        List<DepartmentResponse> ancestors = departmentService.getAncestors(3L);

        assertThat(ancestors).extracting(DepartmentResponse::id).containsExactly(1L, 2L);
    }

    @Test
    void getStatisticsCountsDirectAndTotalDescendants() {
        when(departmentRepository.existsById(1L)).thenReturn(true);
        when(departmentRepository.countByParentDepartmentId(1L)).thenReturn(2L);
        Department childA = department(2L, 1L);
        Department childB = department(3L, 1L);
        Department grandchild = department(4L, 2L);
        when(departmentRepository.findByParentDepartmentId(1L)).thenReturn(List.of(childA, childB));
        when(departmentRepository.findByParentDepartmentId(2L)).thenReturn(List.of(grandchild));
        when(departmentRepository.findByParentDepartmentId(3L)).thenReturn(List.of());
        when(departmentRepository.findByParentDepartmentId(4L)).thenReturn(List.of());
        when(departmentRepository.findById(1L)).thenReturn(Optional.of(department(1L, null)));
        when(employeeHeadcountClient.getHeadcount(1L)).thenReturn(7);

        var stats = departmentService.getStatistics(1L);

        assertThat(stats.directChildrenCount()).isEqualTo(2);
        assertThat(stats.totalDescendantCount()).isEqualTo(3);
        assertThat(stats.depthFromRoot()).isZero();
        assertThat(stats.employeeHeadcount()).isEqualTo(7);
    }

    @Test
    void getStatisticsReportsANullHeadcountWhenEmployeeServiceCannotAnswer() {
        when(departmentRepository.existsById(1L)).thenReturn(true);
        when(departmentRepository.countByParentDepartmentId(1L)).thenReturn(0L);
        when(departmentRepository.findByParentDepartmentId(1L)).thenReturn(List.of());
        when(departmentRepository.findById(1L)).thenReturn(Optional.of(department(1L, null)));
        when(employeeHeadcountClient.getHeadcount(1L)).thenReturn(null);

        var stats = departmentService.getStatistics(1L);

        assertThat(stats.employeeHeadcount()).isNull();
    }
}
