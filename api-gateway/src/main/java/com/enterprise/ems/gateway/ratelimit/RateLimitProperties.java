package com.enterprise.ems.gateway.ratelimit;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "gateway.rate-limit")
public record RateLimitProperties(int limitForPeriod, Duration refreshPeriod) {

    public RateLimitProperties {
        if (limitForPeriod <= 0) {
            limitForPeriod = 20;
        }
        if (refreshPeriod == null || refreshPeriod.isZero()) {
            refreshPeriod = Duration.ofSeconds(1);
        }
    }
}
