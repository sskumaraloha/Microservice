package com.enterprise.ems.auth.integration;

import com.enterprise.ems.auth.dto.ForgotPasswordRequest;
import com.enterprise.ems.auth.dto.LoginRequest;
import com.enterprise.ems.auth.dto.RefreshRequest;
import com.enterprise.ems.auth.dto.RegisterRequest;
import com.enterprise.ems.auth.dto.ResetPasswordRequest;
import com.enterprise.ems.auth.dto.TokenResponse;
import com.enterprise.ems.auth.dto.UserResponse;
import com.enterprise.ems.auth.dto.VerifyEmailRequest;
import com.enterprise.ems.auth.notification.NotificationPort;
import com.enterprise.ems.auth.security.HeaderAuthenticationFilter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end proof, over real HTTP against a real (H2, MySQL-mode)
 * database with Flyway migrations actually applied, that the whole
 * registration -> login -> refresh -> verify/reset flow works together —
 * not just each service method in isolation (see the unit tests in
 * {@code service/}). A production CI pipeline would run the same flow
 * against a Testcontainers-managed real MySQL instance instead; see
 * {@code MySqlIntegrationTest} for that version, guarded to skip when no
 * Docker daemon is available.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class AuthServiceApplicationIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private RecordingNotificationPort notificationPort;

    @Test
    void registrationLoginRefreshRotationAndEmailVerificationWorkEndToEnd() {
        String email = uniqueEmail();

        ResponseEntity<UserResponse> registerResponse = restTemplate.postForEntity(
                "/api/v1/auth/register",
                new RegisterRequest(email, "correct-horse-battery", "Ada", "Lovelace"),
                UserResponse.class);
        assertThat(registerResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(registerResponse.getBody().emailVerified()).isFalse();

        ResponseEntity<TokenResponse> loginResponse = restTemplate.postForEntity(
                "/api/v1/auth/login", new LoginRequest(email, "correct-horse-battery"), TokenResponse.class);
        assertThat(loginResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        String firstRefreshToken = loginResponse.getBody().refreshToken();

        ResponseEntity<TokenResponse> refreshResponse = restTemplate.postForEntity(
                "/api/v1/auth/refresh", new RefreshRequest(firstRefreshToken), TokenResponse.class);
        assertThat(refreshResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(refreshResponse.getBody().refreshToken()).isNotEqualTo(firstRefreshToken);

        // The old refresh token was rotated out; replaying it must fail now.
        ResponseEntity<String> reuseResponse = restTemplate.postForEntity(
                "/api/v1/auth/refresh", new RefreshRequest(firstRefreshToken), String.class);
        assertThat(reuseResponse.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        String verificationCode = notificationPort.lastCodeFor(email);
        assertThat(verificationCode).isNotNull();

        ResponseEntity<Void> verifyResponse = restTemplate.postForEntity(
                "/api/v1/auth/verify-email", new VerifyEmailRequest(email, verificationCode), Void.class);
        assertThat(verifyResponse.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    @Test
    void registeringTheSameEmailTwiceReturns409() {
        String email = uniqueEmail();
        RegisterRequest request = new RegisterRequest(email, "correct-horse-battery", "Ada", "Lovelace");

        restTemplate.postForEntity("/api/v1/auth/register", request, UserResponse.class);
        ResponseEntity<String> secondAttempt =
                restTemplate.postForEntity("/api/v1/auth/register", request, String.class);

        assertThat(secondAttempt.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void forgotPasswordAndResetPasswordInvalidatesEveryExistingSession() {
        String email = uniqueEmail();
        restTemplate.postForEntity(
                "/api/v1/auth/register",
                new RegisterRequest(email, "old-password-123", "Ada", "Lovelace"),
                UserResponse.class);
        TokenResponse tokens = restTemplate.postForEntity(
                "/api/v1/auth/login", new LoginRequest(email, "old-password-123"), TokenResponse.class).getBody();

        ResponseEntity<Void> forgotResponse = restTemplate.postForEntity(
                "/api/v1/auth/forgot-password", new ForgotPasswordRequest(email), Void.class);
        assertThat(forgotResponse.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);

        String resetCode = notificationPort.lastCodeFor(email);
        ResponseEntity<Void> resetResponse = restTemplate.postForEntity(
                "/api/v1/auth/reset-password",
                new ResetPasswordRequest(email, resetCode, "new-password-456"),
                Void.class);
        assertThat(resetResponse.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        // The session obtained before the reset must no longer work.
        ResponseEntity<String> refreshAfterReset = restTemplate.postForEntity(
                "/api/v1/auth/refresh", new RefreshRequest(tokens.refreshToken()), String.class);
        assertThat(refreshAfterReset.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        // The old password no longer works; the new one does.
        ResponseEntity<String> loginWithOldPassword = restTemplate.postForEntity(
                "/api/v1/auth/login", new LoginRequest(email, "old-password-123"), String.class);
        assertThat(loginWithOldPassword.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        ResponseEntity<TokenResponse> loginWithNewPassword = restTemplate.postForEntity(
                "/api/v1/auth/login", new LoginRequest(email, "new-password-456"), TokenResponse.class);
        assertThat(loginWithNewPassword.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void forgotPasswordReturns202ForAnUnknownEmailWithoutRevealingWhetherItExists() {
        ResponseEntity<Void> response = restTemplate.postForEntity(
                "/api/v1/auth/forgot-password", new ForgotPasswordRequest(uniqueEmail()), Void.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
    }

    @Test
    void listUsersRejectsAnUnauthenticatedRequest() {
        ResponseEntity<String> response = restTemplate.getForEntity("/api/v1/users", String.class);

        assertThat(response.getStatusCode()).isIn(HttpStatus.UNAUTHORIZED, HttpStatus.FORBIDDEN);
    }

    @Test
    void listUsersRejectsAnAuthenticatedNonAdmin() {
        HttpHeaders headers = new HttpHeaders();
        headers.add(HeaderAuthenticationFilter.USER_ID_HEADER, "1");
        headers.add(HeaderAuthenticationFilter.ROLES_HEADER, "EMPLOYEE");

        ResponseEntity<String> response =
                restTemplate.exchange("/api/v1/users", org.springframework.http.HttpMethod.GET,
                        new HttpEntity<>(headers), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void listUsersAllowsAnAuthenticatedAdmin() {
        HttpHeaders headers = new HttpHeaders();
        headers.add(HeaderAuthenticationFilter.USER_ID_HEADER, "1");
        headers.add(HeaderAuthenticationFilter.ROLES_HEADER, "ADMIN");

        ResponseEntity<String> response =
                restTemplate.exchange("/api/v1/users", org.springframework.http.HttpMethod.GET,
                        new HttpEntity<>(headers), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    private String uniqueEmail() {
        return "flow-" + UUID.randomUUID() + "@example.com";
    }

    @TestConfiguration
    static class RecordingNotificationConfig {

        @Bean
        @Primary
        RecordingNotificationPort recordingNotificationPort() {
            return new RecordingNotificationPort();
        }
    }

    static class RecordingNotificationPort implements NotificationPort {

        private final Map<String, String> lastCodeByEmail = new ConcurrentHashMap<>();

        @Override
        public void sendOtpCode(String recipientEmail, String code, String purposeDescription) {
            lastCodeByEmail.put(recipientEmail, code);
        }

        String lastCodeFor(String email) {
            return lastCodeByEmail.get(email);
        }
    }
}
