package com.enterprise.ems.auth.service;

import com.enterprise.ems.auth.domain.OtpPurpose;
import com.enterprise.ems.auth.domain.Role;
import com.enterprise.ems.auth.domain.User;
import com.enterprise.ems.auth.dto.ForgotPasswordRequest;
import com.enterprise.ems.auth.dto.LoginRequest;
import com.enterprise.ems.auth.dto.RegisterRequest;
import com.enterprise.ems.auth.dto.ResetPasswordRequest;
import com.enterprise.ems.auth.dto.TokenResponse;
import com.enterprise.ems.auth.dto.UserResponse;
import com.enterprise.ems.auth.dto.VerifyEmailRequest;
import com.enterprise.ems.auth.exception.AccountDisabledException;
import com.enterprise.ems.auth.exception.EmailAlreadyRegisteredException;
import com.enterprise.ems.auth.exception.InvalidCredentialsException;
import com.enterprise.ems.auth.mapper.UserMapper;
import com.enterprise.ems.auth.repository.RoleRepository;
import com.enterprise.ems.auth.repository.UserRepository;
import com.enterprise.ems.auth.service.impl.AuthServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuthServiceImplTest {

    private UserRepository userRepository;
    private RoleRepository roleRepository;
    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();
    private TokenService tokenService;
    private OtpService otpService;
    private UserMapper userMapper;
    private AuthService authService;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        roleRepository = mock(RoleRepository.class);
        tokenService = mock(TokenService.class);
        otpService = mock(OtpService.class);
        userMapper = mock(UserMapper.class);

        authService = new AuthServiceImpl(userRepository, roleRepository, passwordEncoder, tokenService, otpService, userMapper);

        Role employeeRole = new Role();
        employeeRole.setId(1L);
        employeeRole.setName(Role.EMPLOYEE);
        when(roleRepository.findByName(Role.EMPLOYEE)).thenReturn(Optional.of(employeeRole));
        when(userRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void registerHashesThePasswordAssignsTheDefaultRoleAndSendsAVerificationOtp() {
        when(userRepository.existsByEmail("new@example.com")).thenReturn(false);
        when(userMapper.toResponse(any())).thenReturn(
                new UserResponse(1L, "new@example.com", "Ada", "Lovelace", false, Set.of(Role.EMPLOYEE)));

        RegisterRequest request = new RegisterRequest("new@example.com", "correct-horse-battery", "Ada", "Lovelace");
        UserResponse response = authService.register(request);

        assertThat(response.email()).isEqualTo("new@example.com");

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());
        assertThat(captor.getValue().getPasswordHash()).isNotEqualTo("correct-horse-battery");
        assertThat(passwordEncoder.matches("correct-horse-battery", captor.getValue().getPasswordHash())).isTrue();
        assertThat(captor.getValue().getRoles()).extracting(Role::getName).containsExactly(Role.EMPLOYEE);

        verify(otpService).generateAndSend(captor.getValue(), OtpPurpose.EMAIL_VERIFICATION);
    }

    @Test
    void registerRejectsAnAlreadyRegisteredEmailWithoutTouchingTheRepository() {
        when(userRepository.existsByEmail("taken@example.com")).thenReturn(true);

        RegisterRequest request = new RegisterRequest("taken@example.com", "correct-horse-battery", "Ada", "Lovelace");

        assertThatThrownBy(() -> authService.register(request)).isInstanceOf(EmailAlreadyRegisteredException.class);
        verify(userRepository, never()).save(any());
    }

    @Test
    void loginIssuesATokenPairForCorrectCredentials() {
        User user = existingUserWithPassword("employee@example.com", "correct-horse-battery");
        when(userRepository.findByEmail("employee@example.com")).thenReturn(Optional.of(user));
        TokenResponse expectedTokens = TokenResponse.of("access", "refresh", 900);
        when(tokenService.issueTokenPair(user)).thenReturn(expectedTokens);

        TokenResponse tokens = authService.login(new LoginRequest("employee@example.com", "correct-horse-battery"));

        assertThat(tokens).isEqualTo(expectedTokens);
    }

    @Test
    void loginRejectsAnUnknownEmailWithTheSameErrorAsAWrongPassword() {
        when(userRepository.findByEmail("nobody@example.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.login(new LoginRequest("nobody@example.com", "whatever12")))
                .isInstanceOf(InvalidCredentialsException.class);
    }

    @Test
    void loginRejectsAWrongPassword() {
        User user = existingUserWithPassword("employee@example.com", "correct-horse-battery");
        when(userRepository.findByEmail("employee@example.com")).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> authService.login(new LoginRequest("employee@example.com", "wrong-password")))
                .isInstanceOf(InvalidCredentialsException.class);
        verify(tokenService, never()).issueTokenPair(any());
    }

    @Test
    void loginRejectsADisabledAccountEvenWithTheCorrectPassword() {
        User user = existingUserWithPassword("employee@example.com", "correct-horse-battery");
        user.setEnabled(false);
        when(userRepository.findByEmail("employee@example.com")).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> authService.login(new LoginRequest("employee@example.com", "correct-horse-battery")))
                .isInstanceOf(AccountDisabledException.class);
    }

    @Test
    void forgotPasswordSendsAnOtpWhenTheEmailExists() {
        User user = existingUserWithPassword("employee@example.com", "correct-horse-battery");
        when(userRepository.findByEmail("employee@example.com")).thenReturn(Optional.of(user));

        authService.forgotPassword(new ForgotPasswordRequest("employee@example.com"));

        verify(otpService).generateAndSend(user, OtpPurpose.PASSWORD_RESET);
    }

    @Test
    void forgotPasswordSilentlyDoesNothingForAnUnknownEmailToAvoidUserEnumeration() {
        when(userRepository.findByEmail("nobody@example.com")).thenReturn(Optional.empty());

        authService.forgotPassword(new ForgotPasswordRequest("nobody@example.com"));

        verify(otpService, never()).generateAndSend(any(), eq(OtpPurpose.PASSWORD_RESET));
    }

    @Test
    void resetPasswordUpdatesThePasswordAndRevokesEveryExistingSession() {
        User user = existingUserWithPassword("employee@example.com", "old-password-123");
        when(userRepository.findByEmail("employee@example.com")).thenReturn(Optional.of(user));

        authService.resetPassword(new ResetPasswordRequest("employee@example.com", "123456", "new-password-456"));

        verify(otpService).verifyAndConsume(user, OtpPurpose.PASSWORD_RESET, "123456");
        assertThat(passwordEncoder.matches("new-password-456", user.getPasswordHash())).isTrue();
        verify(tokenService).revokeAllForUser(user);
    }

    @Test
    void verifyEmailMarksTheUserAsVerifiedAfterAMatchingOtp() {
        User user = existingUserWithPassword("employee@example.com", "correct-horse-battery");
        when(userRepository.findByEmail("employee@example.com")).thenReturn(Optional.of(user));

        authService.verifyEmail(new VerifyEmailRequest("employee@example.com", "654321"));

        verify(otpService).verifyAndConsume(user, OtpPurpose.EMAIL_VERIFICATION, "654321");
        assertThat(user.isEmailVerified()).isTrue();
    }

    private User existingUserWithPassword(String email, String rawPassword) {
        User user = new User();
        user.setId(99L);
        user.setEmail(email);
        user.setPasswordHash(passwordEncoder.encode(rawPassword));
        user.setEnabled(true);
        return user;
    }
}
