package com.watchwise.watchwise_api.common.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.UUID;

@Service
public class JwtService {

    private static final String TYPE_CLAIM = "type";
    private static final String AUTH_VERSION_CLAIM = "authVersion";
    private static final String REMEMBER_ME_CLAIM = "rememberMe";

    private final SecretKey key;
    private final long accessTokenExpirationMinutes;
    private final long refreshTokenExpirationDays;
    private final long rememberMeRefreshTokenExpirationDays;

    @Autowired
    public JwtService(
            @Value("${app.jwt.secret}") String secret,
            @Value("${app.jwt.expiration-minutes}") long accessTokenExpirationMinutes,
            @Value("${app.jwt.refresh-expiration-days}") long refreshTokenExpirationDays,
            @Value("${app.jwt.remember-me-refresh-expiration-days:30}") long rememberMeRefreshTokenExpirationDays
    ) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.accessTokenExpirationMinutes = accessTokenExpirationMinutes;
        this.refreshTokenExpirationDays = refreshTokenExpirationDays;
        this.rememberMeRefreshTokenExpirationDays = rememberMeRefreshTokenExpirationDays;
    }

    public JwtService(String secret, long accessTokenExpirationMinutes, long refreshTokenExpirationDays) {
        this(secret, accessTokenExpirationMinutes, refreshTokenExpirationDays, 30);
    }

    public String generateToken(UUID userId, TokenType type) {
        return generateToken(userId, 0L, type);
    }

    public String generateToken(UUID userId, long authVersion, TokenType type) {
        return generateToken(userId, authVersion, type, false);
    }

    public String generateToken(UUID userId, long authVersion, TokenType type, boolean rememberMe) {
        if (authVersion < 0) {
            throw new IllegalArgumentException("Authentication version cannot be negative");
        }
        Instant now = Instant.now();
        long refreshExpirationDays = rememberMe
                ? rememberMeRefreshTokenExpirationDays
                : refreshTokenExpirationDays;
        Instant expiration = type == TokenType.REFRESH
                ? now.plus(refreshExpirationDays, ChronoUnit.DAYS)
                : now.plus(accessTokenExpirationMinutes, ChronoUnit.MINUTES);

        return Jwts.builder()
                .id(UUID.randomUUID().toString())
                .subject(userId.toString())
                .claim(TYPE_CLAIM, type.name())
                .claim(AUTH_VERSION_CLAIM, authVersion)
                .claim(REMEMBER_ME_CLAIM, type == TokenType.REFRESH && rememberMe)
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiration))
                .signWith(key)
                .compact();
    }

    public UUID extractUserId(String token) {
        return UUID.fromString(parseClaims(token).getSubject());
    }

    public UUID extractJti(String token) {
        return UUID.fromString(parseClaims(token).getId());
    }

    public Date extractExpiration(String token) {
        return parseClaims(token).getExpiration();
    }

    public Date extractIssuedAt(String token) {
        return parseClaims(token).getIssuedAt();
    }

    public TokenType extractTokenType(String token) {
        return TokenType.valueOf(parseClaims(token).get(TYPE_CLAIM, String.class));
    }

    public long extractAuthVersion(String token) {
        Object value = parseClaims(token).get(AUTH_VERSION_CLAIM);
        if (!(value instanceof Number number) || number.longValue() < 0) {
            throw new IllegalArgumentException("Token has no valid authentication version");
        }
        return number.longValue();
    }

    public boolean extractRememberMe(String token) {
        return Boolean.TRUE.equals(parseClaims(token).get(REMEMBER_ME_CLAIM, Boolean.class));
    }

    public boolean isTokenValid(String token, TokenType expectedType) {
        try {
            Claims claims = parseClaims(token);
            Object authVersion = claims.get(AUTH_VERSION_CLAIM);
            return expectedType.name().equals(claims.get(TYPE_CLAIM, String.class))
                    && authVersion instanceof Number number
                    && number.longValue() >= 0;
        } catch (JwtException | IllegalArgumentException e) {
            return false;
        }
    }

    private Claims parseClaims(String token) {
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}
