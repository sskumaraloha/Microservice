package com.enterprise.ems.auth.service;

import com.enterprise.ems.auth.domain.OtpCode;
import com.enterprise.ems.auth.domain.OtpPurpose;
import com.enterprise.ems.auth.domain.User;
import com.enterprise.ems.auth.exception.InvalidOtpException;
import com.enterprise.ems.auth.notification.NotificationPort;
import com.enterprise.ems.auth.repository.OtpCodeRepository;
import com.enterprise.ems.auth.service.impl.OtpServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OtpServiceImplTest {

    private OtpCodeRepository otpCodeRepository;
    private NotificationPort notificationPort;
    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();
    private OtpService otpService;
    private User user;

    @BeforeEach
    void setUp() {
        otpCodeRepository = mock(OtpCodeRepository.class);
        notificationPort = mock(NotificationPort.class);
        when(otpCodeRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        otpService = new OtpServiceImpl(otpCodeRepository, passwordEncoder, notificationPort);

        user = new User();
        user.setId(7L);
        user.setEmail("employee@example.com");
    }

    @Test
    void generateAndSendPersistsAHashedSixDigitCodeAndSendsThePlaintextOnce() {
        String plaintext = otpService.generateAndSend(user, OtpPurpose.EMAIL_VERIFICATION);

        assertThat(plaintext).matches("\\d{6}");

        ArgumentCaptor<OtpCode> captor = ArgumentCaptor.forClass(OtpCode.class);
        verify(otpCodeRepository).save(captor.capture());
        assertThat(captor.getValue().getCodeHash()).isNotEqualTo(plaintext);
        assertThat(passwordEncoder.matches(plaintext, captor.getValue().getCodeHash())).isTrue();

        verify(notificationPort).sendOtpCode(eq("employee@example.com"), eq(plaintext), any());
    }

    @Test
    void verifyAndConsumeSucceedsForAMatchingUnexpiredCode() {
        OtpCode stored = usableOtp("123456");
        when(otpCodeRepository.findByUserAndPurposeOrderByCreatedAtDesc(user, OtpPurpose.EMAIL_VERIFICATION))
                .thenReturn(List.of(stored));

        otpService.verifyAndConsume(user, OtpPurpose.EMAIL_VERIFICATION, "123456");

        assertThat(stored.getConsumedAt()).isNotNull();
    }

    @Test
    void verifyAndConsumeRejectsAWrongCode() {
        OtpCode stored = usableOtp("123456");
        when(otpCodeRepository.findByUserAndPurposeOrderByCreatedAtDesc(user, OtpPurpose.EMAIL_VERIFICATION))
                .thenReturn(List.of(stored));

        assertThatThrownBy(() -> otpService.verifyAndConsume(user, OtpPurpose.EMAIL_VERIFICATION, "000000"))
                .isInstanceOf(InvalidOtpException.class);
        assertThat(stored.getConsumedAt()).isNull();
    }

    @Test
    void verifyAndConsumeRejectsAnExpiredCode() {
        OtpCode expired = usableOtp("123456");
        expired.setExpiresAt(Instant.now().minusSeconds(1));
        when(otpCodeRepository.findByUserAndPurposeOrderByCreatedAtDesc(user, OtpPurpose.EMAIL_VERIFICATION))
                .thenReturn(List.of(expired));

        assertThatThrownBy(() -> otpService.verifyAndConsume(user, OtpPurpose.EMAIL_VERIFICATION, "123456"))
                .isInstanceOf(InvalidOtpException.class);
    }

    @Test
    void verifyAndConsumeRejectsAnAlreadyConsumedCode() {
        OtpCode consumed = usableOtp("123456");
        consumed.setConsumedAt(Instant.now().minusSeconds(1));
        when(otpCodeRepository.findByUserAndPurposeOrderByCreatedAtDesc(user, OtpPurpose.EMAIL_VERIFICATION))
                .thenReturn(List.of(consumed));

        assertThatThrownBy(() -> otpService.verifyAndConsume(user, OtpPurpose.EMAIL_VERIFICATION, "123456"))
                .isInstanceOf(InvalidOtpException.class);
    }

    private OtpCode usableOtp(String plaintextCode) {
        OtpCode otp = new OtpCode();
        otp.setUser(user);
        otp.setPurpose(OtpPurpose.EMAIL_VERIFICATION);
        otp.setCodeHash(passwordEncoder.encode(plaintextCode));
        otp.setExpiresAt(Instant.now().plusSeconds(600));
        return otp;
    }
}
