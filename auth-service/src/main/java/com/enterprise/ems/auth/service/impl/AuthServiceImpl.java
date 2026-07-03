package com.enterprise.ems.auth.service.impl;

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
import com.enterprise.ems.auth.exception.InvalidOtpException;
import com.enterprise.ems.auth.mapper.UserMapper;
import com.enterprise.ems.auth.repository.RoleRepository;
import com.enterprise.ems.auth.repository.UserRepository;
import com.enterprise.ems.auth.service.AuthService;
import com.enterprise.ems.auth.service.OtpService;
import com.enterprise.ems.auth.service.TokenService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;

@Service
public class AuthServiceImpl implements AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthServiceImpl.class);

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final PasswordEncoder passwordEncoder;
    private final TokenService tokenService;
    private final OtpService otpService;
    private final UserMapper userMapper;

    public AuthServiceImpl(UserRepository userRepository,
                            RoleRepository roleRepository,
                            PasswordEncoder passwordEncoder,
                            TokenService tokenService,
                            OtpService otpService,
                            UserMapper userMapper) {
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
        this.passwordEncoder = passwordEncoder;
        this.tokenService = tokenService;
        this.otpService = otpService;
        this.userMapper = userMapper;
    }

    @Override
    @Transactional
    public UserResponse register(RegisterRequest request) {
        if (userRepository.existsByEmail(request.email())) {
            throw new EmailAlreadyRegisteredException(request.email());
        }

        Role defaultRole = roleRepository.findByName(Role.EMPLOYEE)
                .orElseThrow(() -> new IllegalStateException("Default role EMPLOYEE is missing — check Flyway seed data"));

        User user = new User();
        user.setEmail(request.email());
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.setFirstName(request.firstName());
        user.setLastName(request.lastName());
        user.setRoles(Set.of(defaultRole));
        userRepository.save(user);

        otpService.generateAndSend(user, OtpPurpose.EMAIL_VERIFICATION);

        return userMapper.toResponse(user);
    }

    @Override
    @Transactional
    public TokenResponse login(LoginRequest request) {
        User user = userRepository.findByEmail(request.email())
                // Same exception for "no such user" and "wrong password" —
                // revealing which one it was lets an attacker enumerate
                // valid emails one guess at a time.
                .orElseThrow(InvalidCredentialsException::new);

        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw new InvalidCredentialsException();
        }
        if (!user.isEnabled()) {
            throw new AccountDisabledException();
        }

        return tokenService.issueTokenPair(user);
    }

    @Override
    @Transactional
    public TokenResponse refresh(String refreshToken) {
        return tokenService.rotateRefreshToken(refreshToken);
    }

    @Override
    @Transactional
    public void logout(String refreshToken) {
        tokenService.revoke(refreshToken);
    }

    @Override
    @Transactional
    public void verifyEmail(VerifyEmailRequest request) {
        User user = userRepository.findByEmail(request.email())
                .orElseThrow(InvalidOtpException::new);

        otpService.verifyAndConsume(user, OtpPurpose.EMAIL_VERIFICATION, request.code());
        user.setEmailVerified(true);
        userRepository.save(user);
    }

    @Override
    @Transactional
    public void forgotPassword(ForgotPasswordRequest request) {
        // Deliberately does not throw for an unknown email: an error here
        // vs. success would let an attacker enumerate registered accounts
        // simply by trying emails against "forgot password." The API
        // contract is "if this email exists, a code was sent" without ever
        // confirming which branch actually happened.
        userRepository.findByEmail(request.email())
                .ifPresentOrElse(
                        user -> otpService.generateAndSend(user, OtpPurpose.PASSWORD_RESET),
                        () -> log.debug("Password reset requested for unknown email; no code sent"));
    }

    @Override
    @Transactional
    public void resetPassword(ResetPasswordRequest request) {
        User user = userRepository.findByEmail(request.email())
                .orElseThrow(InvalidOtpException::new);

        otpService.verifyAndConsume(user, OtpPurpose.PASSWORD_RESET, request.code());
        user.setPasswordHash(passwordEncoder.encode(request.newPassword()));
        userRepository.save(user);

        // A password change invalidates every existing session, not just
        // the device that requested the reset — otherwise a stolen refresh
        // token would survive the very act meant to lock the attacker out.
        tokenService.revokeAllForUser(user);
    }
}
