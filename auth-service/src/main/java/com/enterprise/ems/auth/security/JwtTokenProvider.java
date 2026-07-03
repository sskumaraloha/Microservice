package com.enterprise.ems.auth.security;

import com.enterprise.ems.auth.domain.Role;
import com.enterprise.ems.auth.domain.User;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.stream.Collectors;

/**
 * Signs access tokens with the same shared secret the API Gateway verifies
 * against (see {@code config-repo/application.yml}). This service never
 * needs to PARSE a token it receives — it trusts the Gateway's
 * {@code X-Auth-*} headers like every other downstream service (see
 * {@link HeaderAuthenticationFilter}) — so only the signing side of jjwt is
 * used here.
 */
@Component
public class JwtTokenProvider {

    private final SecretKey signingKey;
    private final Duration accessTokenTtl;

    public JwtTokenProvider(JwtProperties properties) {
        this.signingKey = Keys.hmacShaKeyFor(properties.secret().getBytes(StandardCharsets.UTF_8));
        this.accessTokenTtl = Duration.ofMinutes(properties.accessTokenTtlMinutes());
    }

    public String generateAccessToken(User user) {
        Instant now = Instant.now();
        String roles = user.getRoles().stream().map(Role::getName).collect(Collectors.joining(","));

        return Jwts.builder()
                .subject(user.getId().toString())
                .claim("email", user.getEmail())
                .claim("roles", roles)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(accessTokenTtl)))
                .signWith(signingKey)
                .compact();
    }

    public Duration getAccessTokenTtl() {
        return accessTokenTtl;
    }
}
