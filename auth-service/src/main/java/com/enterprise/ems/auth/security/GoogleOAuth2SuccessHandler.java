package com.enterprise.ems.auth.security;

import com.enterprise.ems.auth.domain.Role;
import com.enterprise.ems.auth.domain.User;
import com.enterprise.ems.auth.dto.TokenResponse;
import com.enterprise.ems.auth.repository.RoleRepository;
import com.enterprise.ems.auth.repository.UserRepository;
import com.enterprise.ems.auth.service.TokenService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.util.Set;
import java.util.UUID;

/**
 * Runs after Spring Security has already completed the Google OAuth2
 * Authorization Code exchange — this class never talks to Google directly.
 * It finds-or-creates a LOCAL user record for the Google-verified email and
 * issues our own access/refresh token pair, so the rest of the platform
 * (Gateway, every downstream service) only ever has to understand one
 * token format regardless of how the user originally authenticated.
 */
@Component
public class GoogleOAuth2SuccessHandler implements AuthenticationSuccessHandler {

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final PasswordEncoder passwordEncoder;
    private final TokenService tokenService;
    private final ObjectMapper objectMapper;

    public GoogleOAuth2SuccessHandler(UserRepository userRepository,
                                       RoleRepository roleRepository,
                                       PasswordEncoder passwordEncoder,
                                       TokenService tokenService,
                                       ObjectMapper objectMapper) {
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
        this.passwordEncoder = passwordEncoder;
        this.tokenService = tokenService;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional
    public void onAuthenticationSuccess(HttpServletRequest request,
                                         HttpServletResponse response,
                                         Authentication authentication) throws IOException {
        OAuth2User oauth2User = (OAuth2User) authentication.getPrincipal();
        String email = oauth2User.getAttribute("email");
        String firstName = oauth2User.getAttribute("given_name");
        String lastName = oauth2User.getAttribute("family_name");

        User user = userRepository.findByEmail(email)
                .orElseGet(() -> createFederatedUser(email, firstName, lastName));

        TokenResponse tokens = tokenService.issueTokenPair(user);

        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getWriter(), tokens);
    }

    private User createFederatedUser(String email, String firstName, String lastName) {
        Role employeeRole = roleRepository.findByName(Role.EMPLOYEE)
                .orElseThrow(() -> new IllegalStateException("Default role EMPLOYEE is missing — check Flyway seed data"));

        User user = new User();
        user.setEmail(email);
        user.setFirstName(firstName == null ? "" : firstName);
        user.setLastName(lastName == null ? "" : lastName);
        // Federated accounts never log in with a password — a random,
        // never-disclosed hash makes the password field structurally
        // unusable for a direct /login attempt against this account.
        user.setPasswordHash(passwordEncoder.encode(UUID.randomUUID().toString()));
        // Google already verified this address; asking the user to verify
        // it again would just be friction with no security benefit.
        user.setEmailVerified(true);
        user.setRoles(Set.of(employeeRole));
        return userRepository.save(user);
    }
}
