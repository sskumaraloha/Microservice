package com.enterprise.ems.department.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.preauth.PreAuthenticatedAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Same trusted-header pattern as Auth Service (Step 5) and Employee
 * Service (Step 6): trust {@code X-Auth-User-Id}/{@code X-Auth-Roles},
 * set only by the API Gateway after it already verified the JWT.
 */
public class HeaderAuthenticationFilter extends OncePerRequestFilter {

    public static final String USER_ID_HEADER = "X-Auth-User-Id";
    public static final String ROLES_HEADER = "X-Auth-Roles";

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                     HttpServletResponse response,
                                     FilterChain filterChain) throws ServletException, IOException {
        String userId = request.getHeader(USER_ID_HEADER);

        if (userId != null && !userId.isBlank()) {
            List<GrantedAuthority> authorities = parseRoles(request.getHeader(ROLES_HEADER));
            var authentication = new PreAuthenticatedAuthenticationToken(userId, null, authorities);
            authentication.setAuthenticated(true);
            SecurityContextHolder.getContext().setAuthentication(authentication);
        }

        filterChain.doFilter(request, response);
    }

    private List<GrantedAuthority> parseRoles(String rolesHeader) {
        return Arrays.stream(Optional.ofNullable(rolesHeader).orElse("").split(","))
                .map(String::trim)
                .filter(role -> !role.isEmpty())
                .map(role -> new SimpleGrantedAuthority("ROLE_" + role))
                .collect(Collectors.toList());
    }
}
