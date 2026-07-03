package com.enterprise.ems.auth.service;

import com.enterprise.ems.auth.domain.OtpPurpose;
import com.enterprise.ems.auth.domain.User;

public interface OtpService {

    /** Generates a new code, persists its hash, and returns the plaintext (for sending only — never stored). */
    String generateAndSend(User user, OtpPurpose purpose);

    /**
     * @throws com.enterprise.ems.auth.exception.InvalidOtpException if no usable code matches
     */
    void verifyAndConsume(User user, OtpPurpose purpose, String plaintextCode);
}
