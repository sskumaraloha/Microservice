package com.enterprise.ems.auth.config;

import com.enterprise.ems.auth.security.GoogleOAuth2SuccessHandler;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Only activates when Google credentials are actually configured (Spring
 * Boot only creates a {@link ClientRegistrationRepository} bean once at
 * least one OAuth2 client registration exists — normally set via the
 * {@code SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_GOOGLE_CLIENTID} /
 * {@code ..._CLIENTSECRET} environment variables, never hardcoded in
 * {@code application.yml}). Without those variables set, this class
 * produces no bean at all — the application starts with only the
 * stateless {@link SecurityConfig} chain, and Google login is simply
 * absent rather than a startup failure waiting to happen.
 */
@Configuration
public class GoogleOAuth2SecurityConfig {

    @Bean
    @Order(1)
    @ConditionalOnBean(ClientRegistrationRepository.class)
    public SecurityFilterChain googleOAuth2FilterChain(HttpSecurity http,
                                                         GoogleOAuth2SuccessHandler successHandler) throws Exception {
        http.securityMatcher("/oauth2/**", "/login/**")
                .authorizeHttpRequests(authorize -> authorize.anyRequest().permitAll())
                .oauth2Login(oauth2 -> oauth2.successHandler(successHandler));
        return http.build();
    }
}
