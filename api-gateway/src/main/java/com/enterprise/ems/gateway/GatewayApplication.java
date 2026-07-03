package com.enterprise.ems.gateway;

import com.enterprise.ems.gateway.config.CorsProperties;
import com.enterprise.ems.gateway.ratelimit.RateLimitProperties;
import com.enterprise.ems.gateway.security.JwtProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * The single entry point every client (web, mobile, API consumer) talks
 * to. Routes to downstream services by their Eureka application name,
 * verifies JWTs at the edge before a request is proxied anywhere, and
 * shields downstream services from abusive clients with per-IP rate
 * limiting.
 */
@EnableConfigurationProperties({JwtProperties.class, RateLimitProperties.class, CorsProperties.class})
@SpringBootApplication
public class GatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(GatewayApplication.class, args);
    }
}
