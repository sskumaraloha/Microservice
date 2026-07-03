package com.enterprise.ems.auth.exception;

public class UserNotFoundException extends RuntimeException {

    public UserNotFoundException(Long id) {
        super("No user found with id '%d'".formatted(id));
    }
}
