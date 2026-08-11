package com.watchwise.watchwise_api.auth.service;

import com.watchwise.watchwise_api.auth.dto.RefreshedTokens;

import java.util.UUID;

public interface RefreshTokenService {

    String issueRefreshToken(UUID userId);

    RefreshedTokens rotateRefreshToken(String rawRefreshToken);

    void revokeRefreshToken(String rawRefreshToken);

    void revokeAllRefreshTokens(UUID userId);

    int cleanupExpiredAndRevokedTokens();

}
