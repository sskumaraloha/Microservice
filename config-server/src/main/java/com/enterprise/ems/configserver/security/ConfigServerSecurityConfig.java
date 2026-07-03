package com.enterprise.ems.configserver.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

/**
 * The Config Server hands out datasource URLs, JWT secrets, and other
 * per-service configuration over plain REST (e.g. {@code GET
 * /employee-service/default}). Anyone able to reach it unauthenticated could
 * read every service's secrets in one request. Only the actuator health/info
 * endpoints are exempt, for orchestrator probes.
 */
@Configuration
public class ConfigServerSecurityConfig {

    @Bean
    public SecurityFilterChain configServerFilterChain(HttpSecurity http) throws Exception {
        http.authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/actuator/health/**", "/actuator/info").permitAll()
                        .anyRequest().authenticated())
                .httpBasic(Customizer.withDefaults())
                .csrf(csrf -> csrf.disable());
        return http.build();
    }
}
