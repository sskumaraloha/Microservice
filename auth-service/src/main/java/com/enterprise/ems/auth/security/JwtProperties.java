package com.enterprise.ems.auth.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code secret} has no local default: it must come from the Config
 * Server's shared {@code application.yml}, the exact same value the API
 * Gateway verifies tokens against (see Step 4).
 */
@ConfigurationProperties(prefix = "security.jwt")
public record JwtProperties(String secret, int accessTokenTtlMinutes, int refreshTokenTtlDays) {

    public JwtProperties {
        if (accessTokenTtlMinutes <= 0) {
            accessTokenTtlMinutes = 15;
        }
        if (refreshTokenTtlDays <= 0) {
            refreshTokenTtlDays = 7;
        }
    }
}
