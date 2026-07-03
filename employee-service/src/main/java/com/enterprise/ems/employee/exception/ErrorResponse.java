package com.enterprise.ems.employee.exception;

import java.util.Map;

public record ErrorResponse(int status, String error, String message, Map<String, String> fieldErrors) {

    public static ErrorResponse of(int status, String error, String message) {
        return new ErrorResponse(status, error, message, null);
    }
}
