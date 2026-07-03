package com.enterprise.ems.auth.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * The same {@link PasswordEncoder} bean hashes both user passwords and OTP
 * codes. Both are secrets an attacker could try to brute-force offline if
 * the database leaked, so both deserve BCrypt's deliberately slow, salted
 * hashing — unlike refresh tokens (see {@code TokenServiceImpl}), which are
 * high-entropy and hashed with fast SHA-256 purely for exact-match lookup.
 */
@Configuration
public class PasswordEncoderConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
