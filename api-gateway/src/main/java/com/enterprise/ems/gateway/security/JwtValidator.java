package com.enterprise.ems.gateway.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;

/**
 * Verifies a token's signature and expiry against the shared signing key
 * (see {@code config-repo/application.yml}'s {@code security.jwt.secret}
 * on the Config Server — the same key auth-service signs with in Step 5).
 * This is intentionally the ONLY check performed at the gateway: it proves
 * "this token was issued by our auth-service and hasn't expired," not
 * "this specific user may perform this specific action." Fine-grained
 * authorization stays in each downstream service, which is closer to the
 * data it's protecting and knows its own business rules.
 */
@Component
public class JwtValidator {

    private final SecretKey signingKey;

    public JwtValidator(JwtProperties properties) {
        this.signingKey = Keys.hmacShaKeyFor(properties.secret().getBytes(StandardCharsets.UTF_8));
    }

    /**
     * @throws io.jsonwebtoken.JwtException if the token is malformed, has
     *                                       an invalid signature, or is expired
     */
    public Claims validate(String token) {
        return Jwts.parser()
                .verifyWith(signingKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}
