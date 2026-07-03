package com.enterprise.ems.auth.notification;

/**
 * Auth Service does not send email/SMS itself — that becomes Notification
 * Service's job (Step 14), reacting to a Kafka event this service will
 * publish once Step 9 (event-driven architecture) exists. Until then, this
 * port is implemented by {@link LoggingNotificationAdapter} so the rest of
 * the registration/reset flows can be built, tested, and used end to end
 * today without waiting on infrastructure two services away.
 */
public interface NotificationPort {

    void sendOtpCode(String recipientEmail, String code, String purposeDescription);
}
