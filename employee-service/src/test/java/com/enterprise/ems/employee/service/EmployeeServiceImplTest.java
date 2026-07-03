package com.enterprise.ems.employee.service;

import com.enterprise.ems.employee.domain.Employee;
import com.enterprise.ems.employee.domain.EmployeeStatus;
import com.enterprise.ems.employee.dto.EmployeeRequest;
import com.enterprise.ems.employee.dto.EmployeeResponse;
import com.enterprise.ems.employee.dto.EmployeeSearchCriteria;
import com.enterprise.ems.employee.exception.DuplicateEmailException;
import com.enterprise.ems.employee.exception.EmployeeNotFoundException;
import com.enterprise.ems.employee.exception.InvalidDepartmentException;
import com.enterprise.ems.employee.mapper.EmployeeMapper;
import com.enterprise.ems.employee.mapper.EmployeeMapperImpl;
import com.enterprise.ems.employee.repository.EmployeeRepository;
import com.enterprise.ems.employee.service.impl.EmployeeServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EmployeeServiceImplTest {

    private EmployeeRepository employeeRepository;
    private DepartmentValidationService departmentValidationService;
    private final EmployeeMapper employeeMapper = new EmployeeMapperImpl();
    private EmployeeService employeeService;

    @BeforeEach
    void setUp() {
        employeeRepository = mock(EmployeeRepository.class);
        departmentValidationService = mock(DepartmentValidationService.class);
        employeeService = new EmployeeServiceImpl(employeeRepository, employeeMapper, departmentValidationService);
        when(employeeRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(departmentValidationService.departmentExists(any())).thenReturn(true);
    }

    private EmployeeRequest validRequest() {
        return new EmployeeRequest("Ada", "Lovelace", "ada@example.com", "+1 555 0100", "Engineer", 1L,
                LocalDate.of(2020, 1, 15));
    }

    @Test
    void createSavesANewEmployeeWhenTheEmailIsFree() {
        when(employeeRepository.existsByEmail("ada@example.com")).thenReturn(false);

        EmployeeResponse response = employeeService.create(validRequest());

        assertThat(response.email()).isEqualTo("ada@example.com");
        assertThat(response.status()).isEqualTo(EmployeeStatus.ACTIVE);
        verify(employeeRepository).save(any(Employee.class));
    }

    @Test
    void createRejectsADuplicateEmailWithoutSaving() {
        when(employeeRepository.existsByEmail("ada@example.com")).thenReturn(true);

        assertThatThrownBy(() -> employeeService.create(validRequest()))
                .isInstanceOf(DuplicateEmailException.class);
        verify(employeeRepository, never()).save(any());
    }

    @Test
    void createRejectsANonExistentDepartmentWithoutSaving() {
        when(employeeRepository.existsByEmail("ada@example.com")).thenReturn(false);
        when(departmentValidationService.departmentExists(1L)).thenReturn(false);

        assertThatThrownBy(() -> employeeService.create(validRequest()))
                .isInstanceOf(InvalidDepartmentException.class);
        verify(employeeRepository, never()).save(any());
    }

    @Test
    void getByIdThrowsWhenTheEmployeeDoesNotExist() {
        when(employeeRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> employeeService.getById(99L)).isInstanceOf(EmployeeNotFoundException.class);
    }

    @Test
    void getByIdReturnsTheMappedEmployee() {
        Employee employee = employeeWithId(1L);
        when(employeeRepository.findById(1L)).thenReturn(Optional.of(employee));

        EmployeeResponse response = employeeService.getById(1L);

        assertThat(response.id()).isEqualTo(1L);
        assertThat(response.email()).isEqualTo("ada@example.com");
    }

    @Test
    void searchDelegatesToTheRepositoryWithASpecificationAndMapsTheResultPage() {
        Employee employee = employeeWithId(1L);
        Pageable pageable = Pageable.ofSize(10);
        Page<Employee> page = new PageImpl<>(List.of(employee), pageable, 1);
        when(employeeRepository.findAll(any(Specification.class), eq(pageable))).thenReturn(page);

        Page<EmployeeResponse> result = employeeService.search(
                new EmployeeSearchCriteria("Ada", null, null, null), pageable);

        assertThat(result.getTotalElements()).isEqualTo(1);
        assertThat(result.getContent().get(0).email()).isEqualTo("ada@example.com");
    }

    @Test
    void updateRejectsAnEmailAlreadyUsedByAnotherEmployee() {
        Employee employee = employeeWithId(1L);
        when(employeeRepository.findById(1L)).thenReturn(Optional.of(employee));
        when(employeeRepository.existsByEmailAndIdNot("taken@example.com", 1L)).thenReturn(true);

        EmployeeRequest request = new EmployeeRequest("Ada", "Lovelace", "taken@example.com", null, null, 1L,
                LocalDate.of(2020, 1, 15));

        assertThatThrownBy(() -> employeeService.update(1L, request)).isInstanceOf(DuplicateEmailException.class);
    }

    @Test
    void updateRejectsANonExistentDepartment() {
        Employee employee = employeeWithId(1L);
        when(employeeRepository.findById(1L)).thenReturn(Optional.of(employee));
        when(departmentValidationService.departmentExists(2L)).thenReturn(false);

        EmployeeRequest request = new EmployeeRequest("Ada", "Lovelace", "ada@example.com", null, null, 2L,
                LocalDate.of(2020, 1, 15));

        assertThatThrownBy(() -> employeeService.update(1L, request)).isInstanceOf(InvalidDepartmentException.class);
    }

    @Test
    void countDelegatesToTheRepositoryWithASpecification() {
        when(employeeRepository.count(any(Specification.class))).thenReturn(5L);

        long count = employeeService.count(new EmployeeSearchCriteria(null, null, 1L, null));

        assertThat(count).isEqualTo(5L);
    }

    @Test
    void updateStatusPersistsTheNewStatus() {
        Employee employee = employeeWithId(1L);
        when(employeeRepository.findById(1L)).thenReturn(Optional.of(employee));

        EmployeeResponse response = employeeService.updateStatus(1L, EmployeeStatus.ON_LEAVE);

        assertThat(response.status()).isEqualTo(EmployeeStatus.ON_LEAVE);
    }

    @Test
    void deleteThrowsWhenTheEmployeeDoesNotExist() {
        when(employeeRepository.existsById(99L)).thenReturn(false);

        assertThatThrownBy(() -> employeeService.delete(99L)).isInstanceOf(EmployeeNotFoundException.class);
        verify(employeeRepository, never()).deleteById(anyLong());
    }

    @Test
    void deleteRemovesAnExistingEmployee() {
        when(employeeRepository.existsById(1L)).thenReturn(true);

        employeeService.delete(1L);

        verify(employeeRepository, times(1)).deleteById(1L);
    }

    private Employee employeeWithId(Long id) {
        Employee employee = new Employee();
        employee.setId(id);
        employee.setFirstName("Ada");
        employee.setLastName("Lovelace");
        employee.setEmail("ada@example.com");
        employee.setDepartmentId(1L);
        employee.setHireDate(LocalDate.of(2020, 1, 15));
        return employee;
    }
}
