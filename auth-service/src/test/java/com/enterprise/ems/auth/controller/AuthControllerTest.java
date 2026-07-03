package com.enterprise.ems.auth.controller;

import com.enterprise.ems.auth.dto.LoginRequest;
import com.enterprise.ems.auth.dto.RegisterRequest;
import com.enterprise.ems.auth.dto.TokenResponse;
import com.enterprise.ems.auth.dto.UserResponse;
import com.enterprise.ems.auth.exception.InvalidCredentialsException;
import com.enterprise.ems.auth.service.AuthService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Security is disabled for this slice ({@code addFilters = false}) because
 * it exists to verify controller <-> service wiring and request
 * validation, not authentication — that is covered separately by
 * {@code HeaderAuthenticationFilterTest} and the full-context
 * {@code AuthServiceApplicationIntegrationTest}.
 */
@WebMvcTest(controllers = AuthController.class)
@AutoConfigureMockMvc(addFilters = false)
@ActiveProfiles("test")
class AuthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private AuthService authService;

    @Test
    void registerReturns201WithTheCreatedUser() throws Exception {
        RegisterRequest request = new RegisterRequest("new@example.com", "correct-horse-battery", "Ada", "Lovelace");
        when(authService.register(any()))
                .thenReturn(new UserResponse(1L, "new@example.com", "Ada", "Lovelace", false, Set.of("EMPLOYEE")));

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.email").value("new@example.com"));
    }

    @Test
    void registerRejectsAnInvalidEmailWith400BeforeReachingTheService() throws Exception {
        RegisterRequest request = new RegisterRequest("not-an-email", "correct-horse-battery", "Ada", "Lovelace");

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.email").exists());
    }

    @Test
    void registerRejectsATooShortPasswordWith400() throws Exception {
        RegisterRequest request = new RegisterRequest("new@example.com", "short", "Ada", "Lovelace");

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.password").exists());
    }

    @Test
    void loginReturns200WithTokensOnSuccess() throws Exception {
        LoginRequest request = new LoginRequest("employee@example.com", "correct-horse-battery");
        when(authService.login(any())).thenReturn(TokenResponse.of("access-tok", "refresh-tok", 900));

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").value("access-tok"))
                .andExpect(jsonPath("$.tokenType").value("Bearer"));
    }

    @Test
    void loginReturns401WhenTheServiceRejectsTheCredentials() throws Exception {
        LoginRequest request = new LoginRequest("employee@example.com", "wrong-password");
        when(authService.login(any())).thenThrow(new InvalidCredentialsException());

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Invalid email or password"));
    }

    @Test
    void forgotPasswordAlwaysReturns202RegardlessOfWhetherTheEmailExists() throws Exception {
        mockMvc.perform(post("/api/v1/auth/forgot-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"nobody@example.com\"}"))
                .andExpect(status().isAccepted());
    }
}
