package com.enterprise.ems.auth.dto;

import java.util.Set;

public record UserResponse(
        Long id,
        String email,
        String firstName,
        String lastName,
        boolean emailVerified,
        Set<String> roles
) {
}
