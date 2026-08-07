package com.watchwise.watchwise_api.common.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class JwtServiceTest {

    private final String secret = "test-secret-test-secret-test-secret-test-secret";

    private final JwtService jwtService = new JwtService(secret, 60);

    @Test
    @DisplayName("[generateToken] Should Generate Token That Resolves To Same User Id - When Called")
    void shouldGenerateTokenThatResolvesToSameUserIdWhenCalled() {
        UUID userId = UUID.randomUUID();

        String token = jwtService.generateToken(userId, "user@email.com", TokenType.ACCESS);

        assertThat(jwtService.extractUserId(token)).isEqualTo(userId);
    }

    @Test
    @DisplayName("[generateToken] Should Generate Token With Matching Type Claim - When Called")
    void shouldGenerateTokenWithMatchingTypeClaimWhenCalled() {
        String accessToken = jwtService.generateToken(UUID.randomUUID(), "user@email.com", TokenType.ACCESS);
        String refreshToken = jwtService.generateToken(UUID.randomUUID(), "user@email.com", TokenType.REFRESH);

        assertThat(jwtService.extractTokenType(accessToken)).isEqualTo(TokenType.ACCESS);
        assertThat(jwtService.extractTokenType(refreshToken)).isEqualTo(TokenType.REFRESH);
    }

    @Test
    @DisplayName("[generateToken] Should Generate Tokens With Unique Jti - When Called Multiple Times")
    void shouldGenerateTokensWithUniqueJtiWhenCalledMultipleTimes() {
        UUID userId = UUID.randomUUID();

        String firstToken = jwtService.generateToken(userId, "user@email.com", TokenType.ACCESS);
        String secondToken = jwtService.generateToken(userId, "user@email.com", TokenType.ACCESS);

        assertThat(firstToken).isNotEqualTo(secondToken);
    }

    @Test
    @DisplayName("[isTokenValid] Should Return True - When Token Type Matches Expected Type")
    void shouldReturnTrueWhenTokenTypeMatchesExpectedType() {
        String token = jwtService.generateToken(UUID.randomUUID(), "user@email.com", TokenType.ACCESS);

        assertThat(jwtService.isTokenValid(token, TokenType.ACCESS)).isTrue();
    }

    @Test
    @DisplayName("[isTokenValid] Should Return False - When Refresh Token Is Validated As Access Token")
    void shouldReturnFalseWhenRefreshTokenIsValidatedAsAccessToken() {
        String refreshToken = jwtService.generateToken(UUID.randomUUID(), "user@email.com", TokenType.REFRESH);

        assertThat(jwtService.isTokenValid(refreshToken, TokenType.ACCESS)).isFalse();
    }

    @Test
    @DisplayName("[isTokenValid] Should Return False - When Token Is Malformed")
    void shouldReturnFalseWhenTokenIsMalformed() {
        assertThat(jwtService.isTokenValid("not-a-valid-token", TokenType.ACCESS)).isFalse();
    }

    @Test
    @DisplayName("[isTokenValid] Should Return False - When Token Was Signed With Different Secret")
    void shouldReturnFalseWhenTokenWasSignedWithDifferentSecret() {
        String otherSecret = "other-secret-other-secret-other-secret-other-secret";
        JwtService otherJwtService = new JwtService(otherSecret, 60);

        String token = otherJwtService.generateToken(UUID.randomUUID(), "user@email.com", TokenType.ACCESS);

        assertThat(jwtService.isTokenValid(token, TokenType.ACCESS)).isFalse();
    }
}