package com.enterprise.ems.auth.service.impl;

import com.enterprise.ems.auth.domain.RefreshToken;
import com.enterprise.ems.auth.domain.User;
import com.enterprise.ems.auth.dto.TokenResponse;
import com.enterprise.ems.auth.exception.InvalidRefreshTokenException;
import com.enterprise.ems.auth.repository.RefreshTokenRepository;
import com.enterprise.ems.auth.security.JwtProperties;
import com.enterprise.ems.auth.security.JwtTokenProvider;
import com.enterprise.ems.auth.service.TokenService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;

/**
 * Access tokens are stateless JWTs (Step 4's Gateway verifies them without
 * ever calling this service). Refresh tokens are deliberately NOT JWTs:
 * they are high-entropy opaque random strings, stored server-side as a
 * SHA-256 hash, which is what makes them individually revocable — a JWT
 * refresh token can only be invalidated platform-wide with a denylist,
 * while an opaque token can simply be deleted/marked revoked in the table
 * that is its only source of truth.
 */
@Service
public class TokenServiceImpl implements TokenService {

    private static final int REFRESH_TOKEN_BYTES = 32;

    private final RefreshTokenRepository refreshTokenRepository;
    private final JwtTokenProvider jwtTokenProvider;
    private final Duration refreshTokenTtl;
    private final SecureRandom secureRandom = new SecureRandom();

    public TokenServiceImpl(RefreshTokenRepository refreshTokenRepository,
                             JwtTokenProvider jwtTokenProvider,
                             JwtProperties jwtProperties) {
        this.refreshTokenRepository = refreshTokenRepository;
        this.jwtTokenProvider = jwtTokenProvider;
        this.refreshTokenTtl = Duration.ofDays(jwtProperties.refreshTokenTtlDays());
    }

    @Override
    @Transactional
    public TokenResponse issueTokenPair(User user) {
        return buildAndPersistTokenPair(user);
    }

    @Override
    @Transactional
    public TokenResponse rotateRefreshToken(String presentedRefreshToken) {
        String presentedHash = hash(presentedRefreshToken);
        RefreshToken existing = refreshTokenRepository.findByTokenHash(presentedHash)
                .orElseThrow(InvalidRefreshTokenException::new);

        if (!existing.isActive()) {
            // A dead token being presented again means either it expired
            // naturally (harmless) or it was already rotated/revoked and is
            // being replayed (suspicious). We can't tell the difference
            // from the token alone, so we treat it as a possible theft
            // signal and defensively kill every other active session too.
            revokeAllForUser(existing.getUser());
            throw new InvalidRefreshTokenException();
        }

        existing.setRevokedAt(Instant.now());
        refreshTokenRepository.save(existing);

        return buildAndPersistTokenPair(existing.getUser());
    }

    @Override
    @Transactional
    public void revoke(String presentedRefreshToken) {
        refreshTokenRepository.findByTokenHash(hash(presentedRefreshToken))
                .ifPresent(token -> {
                    token.setRevokedAt(Instant.now());
                    refreshTokenRepository.save(token);
                });
    }

    @Override
    @Transactional
    public void revokeAllForUser(User user) {
        List<RefreshToken> active =
                refreshTokenRepository.findByUserAndRevokedAtIsNullAndExpiresAtAfter(user, Instant.now());
        Instant now = Instant.now();
        active.forEach(token -> token.setRevokedAt(now));
        refreshTokenRepository.saveAll(active);
    }

    private TokenResponse buildAndPersistTokenPair(User user) {
        String accessToken = jwtTokenProvider.generateAccessToken(user);

        String rawRefreshToken = generateOpaqueToken();
        RefreshToken refreshToken = new RefreshToken();
        refreshToken.setUser(user);
        refreshToken.setTokenHash(hash(rawRefreshToken));
        refreshToken.setExpiresAt(Instant.now().plus(refreshTokenTtl));
        refreshTokenRepository.save(refreshToken);

        return TokenResponse.of(accessToken, rawRefreshToken, jwtTokenProvider.getAccessTokenTtl().toSeconds());
    }

    private String generateOpaqueToken() {
        byte[] bytes = new byte[REFRESH_TOKEN_BYTES];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String hash(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hashed);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is guaranteed available on every JVM", e);
        }
    }
}
