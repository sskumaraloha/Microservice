-- One table backs both email verification and password reset codes,
-- distinguished by `purpose`, since both are the same underlying concept:
-- a short-lived, single-use secret proving control of the account's email.
CREATE TABLE otp_codes (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id     BIGINT       NOT NULL,
    code_hash   VARCHAR(255) NOT NULL,
    purpose     VARCHAR(30)  NOT NULL,
    expires_at  TIMESTAMP    NOT NULL,
    consumed_at TIMESTAMP    NULL,
    created_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_otp_codes_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
);

CREATE INDEX idx_otp_codes_user_purpose ON otp_codes (user_id, purpose);
