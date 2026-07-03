package com.enterprise.ems.auth.notification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class LoggingNotificationAdapter implements NotificationPort {

    private static final Logger log = LoggerFactory.getLogger(LoggingNotificationAdapter.class);

    @Override
    public void sendOtpCode(String recipientEmail, String code, String purposeDescription) {
        log.info("[stand-in for Notification Service] {} code for {}: {}", purposeDescription, recipientEmail, code);
    }
}
