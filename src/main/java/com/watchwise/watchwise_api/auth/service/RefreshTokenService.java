package com.watchwise.watchwise_api.auth.service;

import com.watchwise.watchwise_api.auth.dto.RefreshedTokens;

import java.util.UUID;

public interface RefreshTokenService {

    String issueRefreshToken(UUID userId);

    String issueRefreshToken(UUID userId, long authVersion);

    RefreshedTokens rotateRefreshToken(String rawRefreshToken);

    void revokeRefreshToken(String rawRefreshToken);

    void invalidateAllSessions(UUID userId);

    void revokeAllRefreshTokens(UUID userId);

    int cleanupExpiredTokens();

}
