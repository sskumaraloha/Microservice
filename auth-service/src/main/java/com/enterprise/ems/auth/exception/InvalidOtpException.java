package com.enterprise.ems.auth.exception;

public class InvalidOtpException extends RuntimeException {

    public InvalidOtpException() {
        super("Verification code is invalid or has expired");
    }
}
