package com.enterprise.ems.auth.repository;

import com.enterprise.ems.auth.domain.RefreshToken;
import com.enterprise.ems.auth.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {

    Optional<RefreshToken> findByTokenHash(String tokenHash);

    List<RefreshToken> findByUserAndRevokedAtIsNullAndExpiresAtAfter(User user, Instant now);
}
