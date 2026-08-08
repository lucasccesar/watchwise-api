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
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
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

    @Override
    public String issueRefreshToken(UUID userId, String email) {
        String token = jwtService.generateToken(userId, email, TokenType.REFRESH);
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
            throw new UnauthorizedException("Invalid refresh token");
        }

        storedToken.setRevoked(true);
        refreshTokenRepository.save(storedToken);

        User user = storedToken.getUser();
        String newAccessToken = jwtService.generateToken(user.getId(), user.getEmail(), TokenType.ACCESS);
        String newRefreshToken = issueRefreshToken(user.getId(), user.getEmail());

        return new RefreshedTokens(newAccessToken, newRefreshToken);
    }

    private LocalDateTime toLocalDateTime(java.util.Date date) {
        return LocalDateTime.ofInstant(date.toInstant(), ZoneId.systemDefault());
    }
}
