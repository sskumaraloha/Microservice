package com.enterprise.ems.auth.security;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.assertj.core.api.Assertions.assertThat;

class HeaderAuthenticationFilterTest {

    private final HeaderAuthenticationFilter filter = new HeaderAuthenticationFilter();

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void populatesTheSecurityContextFromTrustedGatewayHeaders() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(HeaderAuthenticationFilter.USER_ID_HEADER, "42");
        request.addHeader(HeaderAuthenticationFilter.ROLES_HEADER, "ADMIN,EMPLOYEE");

        filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> {
            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
            assertThat(authentication).isNotNull();
            assertThat(authentication.getName()).isEqualTo("42");
            assertThat(authentication.getAuthorities())
                    .extracting(GrantedAuthority::getAuthority)
                    .containsExactlyInAnyOrder("ROLE_ADMIN", "ROLE_EMPLOYEE");
        });
    }

    @Test
    void leavesTheSecurityContextEmptyWhenNoUserIdHeaderIsPresent() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();

        filter.doFilter(request, new MockHttpServletResponse(), (req, res) ->
                assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull());
    }

    @Test
    void toleratesAMissingRolesHeaderByGrantingNoAuthorities() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(HeaderAuthenticationFilter.USER_ID_HEADER, "7");

        filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> {
            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
            assertThat(authentication.getAuthorities()).isEmpty();
        });
    }
}
