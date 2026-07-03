package com.enterprise.ems.auth.security;

import com.enterprise.ems.auth.domain.Role;
import com.enterprise.ems.auth.domain.User;
import com.enterprise.ems.auth.dto.TokenResponse;
import com.enterprise.ems.auth.repository.RoleRepository;
import com.enterprise.ems.auth.repository.UserRepository;
import com.enterprise.ems.auth.service.TokenService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.oauth2.core.user.OAuth2User;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GoogleOAuth2SuccessHandlerTest {

    private UserRepository userRepository;
    private RoleRepository roleRepository;
    private TokenService tokenService;
    private GoogleOAuth2SuccessHandler handler;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        roleRepository = mock(RoleRepository.class);
        tokenService = mock(TokenService.class);
        PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();
        ObjectMapper objectMapper = new ObjectMapper();

        handler = new GoogleOAuth2SuccessHandler(userRepository, roleRepository, passwordEncoder, tokenService, objectMapper);

        Role employeeRole = new Role();
        employeeRole.setId(1L);
        employeeRole.setName(Role.EMPLOYEE);
        when(roleRepository.findByName(Role.EMPLOYEE)).thenReturn(Optional.of(employeeRole));
        when(userRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void createsALocalFederatedAccountOnFirstGoogleLoginAndReturnsTokens() throws Exception {
        when(userRepository.findByEmail("ada@example.com")).thenReturn(Optional.empty());
        when(tokenService.issueTokenPair(any())).thenReturn(TokenResponse.of("access-tok", "refresh-tok", 900));

        MockHttpServletResponse response = new MockHttpServletResponse();
        handler.onAuthenticationSuccess(new MockHttpServletRequest(), response, googleAuthenticationFor("ada@example.com", "Ada", "Lovelace"));

        verify(userRepository).save(argThatUserHasEmail("ada@example.com"));
        assertThat(response.getContentAsString()).contains("access-tok").contains("refresh-tok");
    }

    @Test
    void reusesTheExistingLocalAccountOnASubsequentGoogleLoginInsteadOfCreatingADuplicate() throws Exception {
        User existing = new User();
        existing.setId(5L);
        existing.setEmail("ada@example.com");
        when(userRepository.findByEmail("ada@example.com")).thenReturn(Optional.of(existing));
        when(tokenService.issueTokenPair(existing)).thenReturn(TokenResponse.of("access-tok", "refresh-tok", 900));

        handler.onAuthenticationSuccess(
                new MockHttpServletRequest(), new MockHttpServletResponse(),
                googleAuthenticationFor("ada@example.com", "Ada", "Lovelace"));

        verify(userRepository, never()).save(any());
        verify(tokenService).issueTokenPair(existing);
    }

    private OAuth2AuthenticationToken googleAuthenticationFor(String email, String givenName, String familyName) {
        Map<String, Object> attributes = Map.of(
                "email", email,
                "given_name", givenName,
                "family_name", familyName);
        OAuth2User principal = new DefaultOAuth2User(List.of(new SimpleGrantedAuthority("ROLE_USER")), attributes, "email");
        return new OAuth2AuthenticationToken(principal, principal.getAuthorities(), "google");
    }

    private User argThatUserHasEmail(String email) {
        return org.mockito.ArgumentMatchers.argThat(user -> user != null && email.equals(user.getEmail()));
    }
}
