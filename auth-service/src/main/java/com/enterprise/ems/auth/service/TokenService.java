package com.enterprise.ems.auth.service;

import com.enterprise.ems.auth.domain.User;
import com.enterprise.ems.auth.dto.TokenResponse;

public interface TokenService {

    /**
     * Issues a fresh access token (JWT) and refresh token (opaque, stored
     * hashed) pair for a just-authenticated user.
     */
    TokenResponse issueTokenPair(User user);

    /**
     * Validates a presented refresh token, revokes it, and issues a new
     * token pair. Reusing an already-rotated (or otherwise revoked) token
     * revokes every other active refresh token for that user, since reuse
     * of a dead token is a signal the token was stolen.
     */
    TokenResponse rotateRefreshToken(String presentedRefreshToken);

    /** Revokes a single refresh token (logout from one session). */
    void revoke(String presentedRefreshToken);

    /** Revokes every active refresh token for a user (logout everywhere / forced re-auth after password change). */
    void revokeAllForUser(User user);
}
