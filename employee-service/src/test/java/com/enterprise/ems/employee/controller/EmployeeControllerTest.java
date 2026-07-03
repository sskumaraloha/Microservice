package com.enterprise.ems.employee.controller;

import com.enterprise.ems.employee.domain.EmployeeStatus;
import com.enterprise.ems.employee.dto.EmployeeRequest;
import com.enterprise.ems.employee.dto.EmployeeResponse;
import com.enterprise.ems.employee.exception.DuplicateEmailException;
import com.enterprise.ems.employee.exception.EmployeeNotFoundException;
import com.enterprise.ems.employee.service.EmployeeService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.domain.PageImpl;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = EmployeeController.class)
@AutoConfigureMockMvc(addFilters = false)
@ActiveProfiles("test")
class EmployeeControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private EmployeeService employeeService;

    private EmployeeRequest validRequest() {
        return new EmployeeRequest("Ada", "Lovelace", "ada@example.com", "+1 555 0100", "Engineer", 1L,
                LocalDate.of(2020, 1, 15));
    }

    private EmployeeResponse response() {
        return new EmployeeResponse(1L, "Ada", "Lovelace", "ada@example.com", "+1 555 0100", "Engineer", 1L,
                EmployeeStatus.ACTIVE, LocalDate.of(2020, 1, 15));
    }

    @Test
    void createReturns201() throws Exception {
        when(employeeService.create(any())).thenReturn(response());

        mockMvc.perform(post("/api/v1/employees")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validRequest())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.email").value("ada@example.com"));
    }

    @Test
    void createRejectsAMissingRequiredFieldWith400() throws Exception {
        EmployeeRequest invalid = new EmployeeRequest("", "Lovelace", "not-an-email", null, null, null, null);

        mockMvc.perform(post("/api/v1/employees")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalid)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.firstName").exists())
                .andExpect(jsonPath("$.fieldErrors.email").exists())
                .andExpect(jsonPath("$.fieldErrors.departmentId").exists());
    }

    @Test
    void createReturns409WhenTheServiceReportsADuplicateEmail() throws Exception {
        when(employeeService.create(any())).thenThrow(new DuplicateEmailException("ada@example.com"));

        mockMvc.perform(post("/api/v1/employees")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validRequest())))
                .andExpect(status().isConflict());
    }

    @Test
    void getByIdReturns404WhenTheServiceReportsNotFound() throws Exception {
        when(employeeService.getById(99L)).thenThrow(new EmployeeNotFoundException(99L));

        mockMvc.perform(get("/api/v1/employees/99")).andExpect(status().isNotFound());
    }

    @Test
    void searchReturnsAPageOfEmployees() throws Exception {
        when(employeeService.search(any(), any())).thenReturn(new PageImpl<>(List.of(response())));

        mockMvc.perform(get("/api/v1/employees").param("name", "Ada"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].email").value("ada@example.com"));
    }

    @Test
    void deleteReturns204() throws Exception {
        mockMvc.perform(delete("/api/v1/employees/1")).andExpect(status().isNoContent());
    }
}
