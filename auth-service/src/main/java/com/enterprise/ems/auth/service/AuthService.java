package com.enterprise.ems.auth.service;

import com.enterprise.ems.auth.dto.ForgotPasswordRequest;
import com.enterprise.ems.auth.dto.LoginRequest;
import com.enterprise.ems.auth.dto.RegisterRequest;
import com.enterprise.ems.auth.dto.ResetPasswordRequest;
import com.enterprise.ems.auth.dto.TokenResponse;
import com.enterprise.ems.auth.dto.UserResponse;
import com.enterprise.ems.auth.dto.VerifyEmailRequest;

public interface AuthService {

    UserResponse register(RegisterRequest request);

    TokenResponse login(LoginRequest request);

    TokenResponse refresh(String refreshToken);

    void logout(String refreshToken);

    void verifyEmail(VerifyEmailRequest request);

    /** Always succeeds from the caller's point of view, even for an unknown email — see impl Javadoc. */
    void forgotPassword(ForgotPasswordRequest request);

    void resetPassword(ResetPasswordRequest request);
}
