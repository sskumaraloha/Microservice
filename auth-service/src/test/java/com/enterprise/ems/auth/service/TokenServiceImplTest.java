package com.enterprise.ems.auth.service;

import com.enterprise.ems.auth.domain.RefreshToken;
import com.enterprise.ems.auth.domain.Role;
import com.enterprise.ems.auth.domain.User;
import com.enterprise.ems.auth.dto.TokenResponse;
import com.enterprise.ems.auth.exception.InvalidRefreshTokenException;
import com.enterprise.ems.auth.repository.RefreshTokenRepository;
import com.enterprise.ems.auth.security.JwtProperties;
import com.enterprise.ems.auth.security.JwtTokenProvider;
import com.enterprise.ems.auth.service.impl.TokenServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TokenServiceImplTest {

    private static final String SECRET = "test-only-signing-key-at-least-32-bytes-long-for-hmac-sha256";

    private RefreshTokenRepository refreshTokenRepository;
    private TokenService tokenService;
    private User user;

    @BeforeEach
    void setUp() {
        refreshTokenRepository = mock(RefreshTokenRepository.class);
        when(refreshTokenRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        JwtProperties properties = new JwtProperties(SECRET, 15, 7);
        JwtTokenProvider jwtTokenProvider = new JwtTokenProvider(properties);
        tokenService = new TokenServiceImpl(refreshTokenRepository, jwtTokenProvider, properties);

        Role role = new Role();
        role.setId(1L);
        role.setName(Role.EMPLOYEE);
        user = new User();
        user.setId(42L);
        user.setEmail("employee@example.com");
        user.setRoles(Set.of(role));
    }

    @Test
    void issueTokenPairReturnsAnAccessTokenAndAPersistedHashedRefreshToken() {
        TokenResponse tokens = tokenService.issueTokenPair(user);

        assertThat(tokens.accessToken()).isNotBlank();
        assertThat(tokens.refreshToken()).isNotBlank();
        assertThat(tokens.tokenType()).isEqualTo("Bearer");

        ArgumentCaptor<RefreshToken> captor = ArgumentCaptor.forClass(RefreshToken.class);
        verify(refreshTokenRepository).save(captor.capture());
        // The stored hash must never equal the raw token handed to the client.
        assertThat(captor.getValue().getTokenHash()).isNotEqualTo(tokens.refreshToken());
        assertThat(captor.getValue().getUser()).isEqualTo(user);
    }

    @Test
    void rotateRefreshTokenRevokesTheOldOneAndIssuesANewPair() {
        RefreshToken active = activeRefreshTokenFor(user);
        when(refreshTokenRepository.findByTokenHash(any())).thenReturn(Optional.of(active));

        TokenResponse rotated = tokenService.rotateRefreshToken("whatever-raw-value");

        assertThat(active.getRevokedAt()).isNotNull();
        assertThat(rotated.accessToken()).isNotBlank();
        assertThat(rotated.refreshToken()).isNotBlank();
    }

    @Test
    void rotatingAnAlreadyRevokedTokenIsTreatedAsPossibleTheftAndRevokesEverySessionForThatUser() {
        RefreshToken alreadyRevoked = activeRefreshTokenFor(user);
        alreadyRevoked.setRevokedAt(Instant.now().minusSeconds(60));
        when(refreshTokenRepository.findByTokenHash(any())).thenReturn(Optional.of(alreadyRevoked));

        List<RefreshToken> otherActiveSessions = List.of(activeRefreshTokenFor(user), activeRefreshTokenFor(user));
        when(refreshTokenRepository.findByUserAndRevokedAtIsNullAndExpiresAtAfter(any(), any()))
                .thenReturn(otherActiveSessions);

        assertThatThrownBy(() -> tokenService.rotateRefreshToken("stolen-token"))
                .isInstanceOf(InvalidRefreshTokenException.class);

        assertThat(otherActiveSessions).allSatisfy(token -> assertThat(token.getRevokedAt()).isNotNull());
        verify(refreshTokenRepository).saveAll(otherActiveSessions);
    }

    @Test
    void rotatingAnUnknownTokenThrowsWithoutTouchingAnySessions() {
        when(refreshTokenRepository.findByTokenHash(any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> tokenService.rotateRefreshToken("never-issued"))
                .isInstanceOf(InvalidRefreshTokenException.class);

        verify(refreshTokenRepository, never()).saveAll(any());
    }

    @Test
    void revokeMarksAMatchingTokenAsRevoked() {
        RefreshToken active = activeRefreshTokenFor(user);
        when(refreshTokenRepository.findByTokenHash(any())).thenReturn(Optional.of(active));

        tokenService.revoke("raw-token");

        assertThat(active.getRevokedAt()).isNotNull();
        verify(refreshTokenRepository, times(1)).save(active);
    }

    @Test
    void revokeIsANoOpWhenTheTokenIsUnknown() {
        when(refreshTokenRepository.findByTokenHash(any())).thenReturn(Optional.empty());

        tokenService.revoke("never-issued");

        verify(refreshTokenRepository, never()).save(any());
    }

    private RefreshToken activeRefreshTokenFor(User owner) {
        RefreshToken token = new RefreshToken();
        token.setUser(owner);
        token.setTokenHash("irrelevant-in-this-test");
        token.setExpiresAt(Instant.now().plusSeconds(3600));
        return token;
    }
}
