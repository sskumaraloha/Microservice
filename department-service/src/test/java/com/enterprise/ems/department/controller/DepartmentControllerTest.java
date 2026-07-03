package com.enterprise.ems.department.controller;

import com.enterprise.ems.department.dto.DepartmentRequest;
import com.enterprise.ems.department.dto.DepartmentResponse;
import com.enterprise.ems.department.exception.DepartmentHasChildrenException;
import com.enterprise.ems.department.exception.DuplicateDepartmentException;
import com.enterprise.ems.department.service.DepartmentService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = DepartmentController.class)
@AutoConfigureMockMvc(addFilters = false)
@ActiveProfiles("test")
class DepartmentControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private DepartmentService departmentService;

    private DepartmentRequest validRequest() {
        return new DepartmentRequest("Engineering", "ENG", "Builds the product", null, null);
    }

    @Test
    void createReturns201() throws Exception {
        when(departmentService.create(any()))
                .thenReturn(new DepartmentResponse(1L, "Engineering", "ENG", "Builds the product", null, null));

        mockMvc.perform(post("/api/v1/departments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validRequest())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Engineering"));
    }

    @Test
    void createRejectsAnInvalidCodeWith400() throws Exception {
        DepartmentRequest invalid = new DepartmentRequest("Engineering", "not valid!", null, null, null);

        mockMvc.perform(post("/api/v1/departments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalid)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.code").exists());
    }

    @Test
    void createReturns409ForADuplicateName() throws Exception {
        when(departmentService.create(any())).thenThrow(new DuplicateDepartmentException("A department named 'Engineering' already exists"));

        mockMvc.perform(post("/api/v1/departments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validRequest())))
                .andExpect(status().isConflict());
    }

    @Test
    void deleteReturns409WhenTheDepartmentHasChildren() throws Exception {
        doThrow(new DepartmentHasChildrenException(1L)).when(departmentService).delete(1L);

        mockMvc.perform(delete("/api/v1/departments/1")).andExpect(status().isConflict());
    }

    @Test
    void deleteReturns204WhenSuccessful() throws Exception {
        mockMvc.perform(delete("/api/v1/departments/2")).andExpect(status().isNoContent());
    }
}
