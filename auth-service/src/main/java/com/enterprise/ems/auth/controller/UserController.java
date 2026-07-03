package com.enterprise.ems.auth.controller;

import com.enterprise.ems.auth.dto.UserResponse;
import com.enterprise.ems.auth.exception.UserNotFoundException;
import com.enterprise.ems.auth.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/v1/users/**} is restricted to ADMIN by {@code SecurityConfig}
 * (enforced against the roles the Gateway propagated in {@code X-Auth-Roles}).
 * {@code /me} is intentionally outside that prefix — any authenticated user
 * may read their own profile.
 */
@Tag(name = "Users")
@RestController
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @Operation(summary = "List all users (ADMIN only)")
    @GetMapping("/api/v1/users")
    public Page<UserResponse> listUsers(Pageable pageable) {
        return userService.listUsers(pageable);
    }

    @Operation(summary = "Get the caller's own profile")
    @GetMapping("/api/v1/users/me")
    public UserResponse me(Authentication authentication) {
        Long userId = Long.valueOf(authentication.getName());
        return userService.getById(userId);
    }
}
