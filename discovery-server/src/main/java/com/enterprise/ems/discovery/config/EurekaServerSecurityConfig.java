package com.enterprise.ems.discovery.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;

/**
 * An unauthenticated Eureka dashboard leaks the platform's internal service
 * topology (application names, instance counts, hosts) to anyone who can
 * reach the port. This configuration requires HTTP Basic auth for every
 * request except the actuator health/info endpoints, which container
 * orchestrators must be able to probe without credentials.
 *
 * <p>Eureka clients register via unauthenticated-looking POST/PUT/DELETE
 * calls under {@code /eureka/**}; those calls still carry the Basic auth
 * header (embedded in {@code eureka.client.service-url.defaultZone} as
 * {@code http://user:pass@host:port/eureka/}), but they cannot supply a
 * CSRF token, so CSRF protection is disabled specifically for that path.
 */
@Configuration
public class EurekaServerSecurityConfig {

    @Bean
    public SecurityFilterChain eurekaServerFilterChain(HttpSecurity http) throws Exception {
        http.authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/actuator/health/**", "/actuator/info").permitAll()
                        .anyRequest().authenticated())
                .httpBasic(Customizer.withDefaults())
                .csrf(csrf -> csrf.ignoringRequestMatchers(new AntPathRequestMatcher("/eureka/**")));
        return http.build();
    }
}
