package com.watchwise.watchwise_api.auth.service.impl;

import com.watchwise.watchwise_api.auth.dto.RefreshedTokens;
import com.watchwise.watchwise_api.auth.entity.RefreshToken;
import com.watchwise.watchwise_api.auth.repository.RefreshTokenRepository;
import com.watchwise.watchwise_api.auth.service.RefreshTokenService;
import com.watchwise.watchwise_api.common.exception.UnauthorizedException;
import com.watchwise.watchwise_api.common.security.JwtService;
import com.watchwise.watchwise_api.common.security.TokenType;
import com.watchwise.watchwise_api.user.entity.User;
import com.watchwise.watchwise_api.user.repository.UserRepository;
import io.jsonwebtoken.JwtException;
import lombok.RequiredArgsConstructor;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class RefreshTokenServiceImpl implements RefreshTokenService {

    private final RefreshTokenRepository refreshTokenRepository;
    private final UserRepository userRepository;
    private final JwtService jwtService;
    private final PlatformTransactionManager transactionManager;

    @Override
    public String issueRefreshToken(UUID userId) {
        String token = jwtService.generateToken(userId, TokenType.REFRESH);
        UUID jti = jwtService.extractJti(token);

        RefreshToken refreshToken = RefreshToken.builder()
                .id(jti)
                .user(userRepository.getReferenceById(userId))
                .expiresAt(toLocalDateTime(jwtService.extractExpiration(token)))
                .createdAt(LocalDateTime.now())
                .build();

        refreshTokenRepository.save(refreshToken);

        return token;
    }

    @Override
    @Transactional
    public RefreshedTokens rotateRefreshToken(String rawRefreshToken) {
        if (!StringUtils.hasText(rawRefreshToken) || !jwtService.isTokenValid(rawRefreshToken, TokenType.REFRESH)) {
            throw new UnauthorizedException("Invalid refresh token");
        }

        UUID jti = jwtService.extractJti(rawRefreshToken);
        RefreshToken storedToken = refreshTokenRepository.findById(jti)
                .orElseThrow(() -> new UnauthorizedException("Invalid refresh token"));

        if (Boolean.TRUE.equals(storedToken.getRevoked())) {
            revokeAllRefreshTokens(storedToken.getUser().getId());
            throw new UnauthorizedException("Invalid refresh token");
        }

        storedToken.setRevoked(true);
        try {
            refreshTokenRepository.saveAndFlush(storedToken);
        } catch (ObjectOptimisticLockingFailureException e) {
            revokeAllRefreshTokens(storedToken.getUser().getId());
            throw new UnauthorizedException("Invalid refresh token");
        }

        User user = storedToken.getUser();
        String newAccessToken = jwtService.generateToken(user.getId(), TokenType.ACCESS);
        String newRefreshToken = issueRefreshToken(user.getId());

        return new RefreshedTokens(newAccessToken, newRefreshToken);
    }

    @Override
    public void revokeRefreshToken(String rawRefreshToken) {
        if (!StringUtils.hasText(rawRefreshToken)) {
            return;
        }

        UUID jti;
        try {
            jti = jwtService.extractJti(rawRefreshToken);
        } catch (JwtException | IllegalArgumentException e) {
            return;
        }

        refreshTokenRepository.findById(jti).ifPresent(refreshToken -> {
            refreshToken.setRevoked(true);
            refreshTokenRepository.save(refreshToken);
        });
    }

    @Override
    public void revokeAllRefreshTokens(UUID userId) {
        TransactionTemplate requiresNewTransaction = new TransactionTemplate(transactionManager);
        requiresNewTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        requiresNewTransaction.executeWithoutResult(status -> refreshTokenRepository.revokeAllByUserId(userId));
    }

    @Override
    @Transactional
    public int cleanupExpiredTokens() {
        return refreshTokenRepository.deleteExpired(LocalDateTime.now());
    }

    private LocalDateTime toLocalDateTime(java.util.Date date) {
        return LocalDateTime.ofInstant(date.toInstant(), ZoneId.systemDefault());
    }
}
