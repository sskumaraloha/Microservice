package com.enterprise.ems.gateway.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * {@code secret} deliberately has no local default value anywhere in this
 * module's configuration: the gateway must fail fast at startup if it
 * cannot obtain the real signing key from the Config Server, rather than
 * silently falling back to a well-known value that would let anyone forge
 * a valid token.
 */
@ConfigurationProperties(prefix = "security.jwt")
public record JwtProperties(String secret, List<String> publicPaths) {

    public JwtProperties {
        publicPaths = publicPaths == null ? List.of() : List.copyOf(publicPaths);
    }
}
