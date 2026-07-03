package com.enterprise.ems.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@ConfigurationProperties(prefix = "gateway.cors")
public record CorsProperties(List<String> allowedOrigins, List<String> allowedMethods, List<String> allowedHeaders) {

    public CorsProperties {
        allowedOrigins = allowedOrigins == null ? List.of() : List.copyOf(allowedOrigins);
        allowedMethods = (allowedMethods == null || allowedMethods.isEmpty())
                ? List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
                : List.copyOf(allowedMethods);
        allowedHeaders = (allowedHeaders == null || allowedHeaders.isEmpty())
                ? List.of("*")
                : List.copyOf(allowedHeaders);
    }
}
