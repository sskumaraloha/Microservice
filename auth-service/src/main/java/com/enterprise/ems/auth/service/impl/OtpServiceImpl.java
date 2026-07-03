package com.enterprise.ems.auth.service.impl;

import com.enterprise.ems.auth.domain.OtpCode;
import com.enterprise.ems.auth.domain.OtpPurpose;
import com.enterprise.ems.auth.domain.User;
import com.enterprise.ems.auth.exception.InvalidOtpException;
import com.enterprise.ems.auth.notification.NotificationPort;
import com.enterprise.ems.auth.repository.OtpCodeRepository;
import com.enterprise.ems.auth.service.OtpService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * OTP codes are only 6 digits (1 million possibilities) — far too weak for
 * a fast hash. They get the same {@link PasswordEncoder} (BCrypt) as
 * account passwords, verified by fetching the specific user+purpose row
 * first and calling {@code matches()} against it, never by searching for a
 * code by its hash (which BCrypt's random salting makes impossible anyway).
 */
@Service
public class OtpServiceImpl implements OtpService {

    private static final Duration OTP_TTL = Duration.ofMinutes(10);
    private static final int OTP_DIGITS = 6;

    private final OtpCodeRepository otpCodeRepository;
    private final PasswordEncoder passwordEncoder;
    private final NotificationPort notificationPort;
    private final SecureRandom secureRandom = new SecureRandom();

    public OtpServiceImpl(OtpCodeRepository otpCodeRepository,
                           PasswordEncoder passwordEncoder,
                           NotificationPort notificationPort) {
        this.otpCodeRepository = otpCodeRepository;
        this.passwordEncoder = passwordEncoder;
        this.notificationPort = notificationPort;
    }

    @Override
    @Transactional
    public String generateAndSend(User user, OtpPurpose purpose) {
        String plaintextCode = generateSixDigitCode();

        OtpCode otpCode = new OtpCode();
        otpCode.setUser(user);
        otpCode.setPurpose(purpose);
        otpCode.setCodeHash(passwordEncoder.encode(plaintextCode));
        otpCode.setExpiresAt(Instant.now().plus(OTP_TTL));
        otpCodeRepository.save(otpCode);

        notificationPort.sendOtpCode(user.getEmail(), plaintextCode, purpose.name());
        return plaintextCode;
    }

    @Override
    @Transactional
    public void verifyAndConsume(User user, OtpPurpose purpose, String plaintextCode) {
        List<OtpCode> candidates = otpCodeRepository.findByUserAndPurposeOrderByCreatedAtDesc(user, purpose);

        OtpCode match = candidates.stream()
                .filter(OtpCode::isUsable)
                .filter(candidate -> passwordEncoder.matches(plaintextCode, candidate.getCodeHash()))
                .findFirst()
                .orElseThrow(InvalidOtpException::new);

        match.setConsumedAt(Instant.now());
        otpCodeRepository.save(match);
    }

    private String generateSixDigitCode() {
        int value = secureRandom.nextInt((int) Math.pow(10, OTP_DIGITS));
        return String.format("%0" + OTP_DIGITS + "d", value);
    }
}
