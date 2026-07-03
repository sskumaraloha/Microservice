package com.enterprise.ems.employee.security;

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
 * Identical pattern to Auth Service's filter of the same name (Step 5):
 * trust {@code X-Auth-User-Id}/{@code X-Auth-Roles}, set only by the API
 * Gateway after it already verified the JWT. Every downstream service
 * duplicates this small filter rather than depending on a shared library
 * module — a deliberate trade-off favoring true independent
 * deployability over DRY across service boundaries (a shared auth library
 * would mean every service upgrading it in lockstep, which is exactly the
 * coupling microservices are meant to avoid).
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
