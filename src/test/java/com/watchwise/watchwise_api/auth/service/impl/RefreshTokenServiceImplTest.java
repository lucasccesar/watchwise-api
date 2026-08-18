package com.watchwise.watchwise_api.auth.service.impl;

import com.watchwise.watchwise_api.auth.dto.RefreshedTokens;
import com.watchwise.watchwise_api.auth.entity.RefreshToken;
import com.watchwise.watchwise_api.auth.repository.RefreshTokenRepository;
import com.watchwise.watchwise_api.common.exception.UnauthorizedException;
import com.watchwise.watchwise_api.common.security.JwtService;
import com.watchwise.watchwise_api.common.security.TokenType;
import com.watchwise.watchwise_api.user.entity.User;
import com.watchwise.watchwise_api.user.repository.UserRepository;
import io.jsonwebtoken.JwtException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RefreshTokenServiceImplTest {

    @Mock
    private RefreshTokenRepository refreshTokenRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private JwtService jwtService;

    @Mock
    private PlatformTransactionManager transactionManager;

    @InjectMocks
    private RefreshTokenServiceImpl refreshTokenService;

    @Captor
    private ArgumentCaptor<RefreshToken> refreshTokenCaptor;

    private User user;
    private UUID userId;

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
        user = User.builder()
                .id(userId)
                .username("lucas")
                .email("lucas@email.com")
                .password("hashed_password")
                .isProfilePublic(true)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
    }

    @Test
    @DisplayName("[issueRefreshToken] Should Persist Refresh Token With Id Matching Generated Jti - When Called")
    void shouldPersistRefreshTokenWithIdMatchingGeneratedJtiWhenCalled() {
        String token = "refresh-token-value";
        UUID jti = UUID.randomUUID();
        Date expiration = new Date(System.currentTimeMillis() + 60_000);

        when(jwtService.generateToken(userId, TokenType.REFRESH)).thenReturn(token);
        when(jwtService.extractJti(token)).thenReturn(jti);
        when(jwtService.extractExpiration(token)).thenReturn(expiration);
        when(userRepository.getReferenceById(userId)).thenReturn(user);

        String result = refreshTokenService.issueRefreshToken(userId);

        verify(refreshTokenRepository).save(refreshTokenCaptor.capture());
        RefreshToken saved = refreshTokenCaptor.getValue();

        assertThat(result).isEqualTo(token);
        assertThat(saved.getId()).isEqualTo(jti);
        assertThat(saved.getUser()).isEqualTo(user);
        assertThat(saved.getRevoked()).isFalse();
    }

    @Test
    @DisplayName("[rotateRefreshToken] Should Throw UnauthorizedException - When Token Is Blank")
    void shouldThrowUnauthorizedExceptionWhenTokenIsBlank() {
        assertThatThrownBy(() -> refreshTokenService.rotateRefreshToken("  "))
                .isInstanceOf(UnauthorizedException.class);

        verifyNoInteractions(refreshTokenRepository);
    }

    @Test
    @DisplayName("[rotateRefreshToken] Should Throw UnauthorizedException - When Token Signature Or Type Is Invalid")
    void shouldThrowUnauthorizedExceptionWhenTokenSignatureOrTypeIsInvalid() {
        String token = "invalid-token";
        when(jwtService.isTokenValid(token, TokenType.REFRESH)).thenReturn(false);

        assertThatThrownBy(() -> refreshTokenService.rotateRefreshToken(token))
                .isInstanceOf(UnauthorizedException.class);

        verifyNoInteractions(refreshTokenRepository);
    }

    @Test
    @DisplayName("[rotateRefreshToken] Should Throw UnauthorizedException - When Token Is Not Found In Database")
    void shouldThrowUnauthorizedExceptionWhenTokenIsNotFoundInDatabase() {
        String token = "valid-token";
        UUID jti = UUID.randomUUID();

        when(jwtService.isTokenValid(token, TokenType.REFRESH)).thenReturn(true);
        when(jwtService.extractJti(token)).thenReturn(jti);
        when(refreshTokenRepository.findById(jti)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> refreshTokenService.rotateRefreshToken(token))
                .isInstanceOf(UnauthorizedException.class);
    }

    @Test
    @DisplayName("[rotateRefreshToken] Should Throw UnauthorizedException - When Stored Token Is Already Revoked")
    void shouldThrowUnauthorizedExceptionWhenStoredTokenIsAlreadyRevoked() {
        String token = "valid-token";
        UUID jti = UUID.randomUUID();
        RefreshToken storedToken = RefreshToken.builder()
                .id(jti)
                .user(user)
                .revoked(true)
                .expiresAt(LocalDateTime.now().plusDays(7))
                .createdAt(LocalDateTime.now())
                .build();

        when(jwtService.isTokenValid(token, TokenType.REFRESH)).thenReturn(true);
        when(jwtService.extractJti(token)).thenReturn(jti);
        when(refreshTokenRepository.findById(jti)).thenReturn(Optional.of(storedToken));

        assertThatThrownBy(() -> refreshTokenService.rotateRefreshToken(token))
                .isInstanceOf(UnauthorizedException.class);

        verify(refreshTokenRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("[rotateRefreshToken] Should Revoke All Refresh Tokens For The Owner - When Stored Token Is Already Revoked")
    void shouldRevokeAllRefreshTokensForTheOwnerWhenStoredTokenIsAlreadyRevoked() {
        String token = "valid-token";
        UUID jti = UUID.randomUUID();
        RefreshToken storedToken = RefreshToken.builder()
                .id(jti)
                .user(user)
                .revoked(true)
                .expiresAt(LocalDateTime.now().plusDays(7))
                .createdAt(LocalDateTime.now())
                .build();

        when(jwtService.isTokenValid(token, TokenType.REFRESH)).thenReturn(true);
        when(jwtService.extractJti(token)).thenReturn(jti);
        when(refreshTokenRepository.findById(jti)).thenReturn(Optional.of(storedToken));
        when(transactionManager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));

        assertThatThrownBy(() -> refreshTokenService.rotateRefreshToken(token))
                .isInstanceOf(UnauthorizedException.class);

        verify(refreshTokenRepository).revokeAllByUserId(userId);
        verify(userRepository).markSessionsInvalidatedAt(eq(userId), any(LocalDateTime.class));
    }

    @Test
    @DisplayName("[rotateRefreshToken] Should Revoke Old Token And Issue New Token Pair - When Token Is Valid And Not Revoked")
    void shouldRevokeOldTokenAndIssueNewTokenPairWhenTokenIsValidAndNotRevoked() {
        String oldToken = "old-refresh-token";
        String newAccessToken = "new-access-token";
        String newRefreshToken = "new-refresh-token";
        UUID oldJti = UUID.randomUUID();
        UUID newJti = UUID.randomUUID();
        Date newExpiration = new Date(System.currentTimeMillis() + 60_000);

        RefreshToken storedToken = RefreshToken.builder()
                .id(oldJti)
                .user(user)
                .revoked(false)
                .expiresAt(LocalDateTime.now().plusDays(7))
                .createdAt(LocalDateTime.now())
                .build();

        when(jwtService.isTokenValid(oldToken, TokenType.REFRESH)).thenReturn(true);
        when(jwtService.extractJti(oldToken)).thenReturn(oldJti);
        when(refreshTokenRepository.findById(oldJti)).thenReturn(Optional.of(storedToken));
        when(jwtService.generateToken(userId, TokenType.ACCESS)).thenReturn(newAccessToken);
        when(jwtService.generateToken(userId, TokenType.REFRESH)).thenReturn(newRefreshToken);
        when(jwtService.extractJti(newRefreshToken)).thenReturn(newJti);
        when(jwtService.extractExpiration(newRefreshToken)).thenReturn(newExpiration);
        when(userRepository.getReferenceById(userId)).thenReturn(user);

        RefreshedTokens result = refreshTokenService.rotateRefreshToken(oldToken);

        assertThat(result).isEqualTo(new RefreshedTokens(newAccessToken, newRefreshToken));
        assertThat(storedToken.getRevoked()).isTrue();
        verify(refreshTokenRepository).saveAndFlush(storedToken);
        verify(refreshTokenRepository).save(any(RefreshToken.class));
    }

    @Test
    @DisplayName("[rotateRefreshToken] Should Revoke All Refresh Tokens For The Owner And Throw UnauthorizedException - When The Same Token Is Rotated Concurrently")
    void shouldRevokeAllRefreshTokensForTheOwnerAndThrowUnauthorizedExceptionWhenTheSameTokenIsRotatedConcurrently() {
        String token = "valid-token";
        UUID jti = UUID.randomUUID();
        RefreshToken storedToken = RefreshToken.builder()
                .id(jti)
                .user(user)
                .revoked(false)
                .expiresAt(LocalDateTime.now().plusDays(7))
                .createdAt(LocalDateTime.now())
                .build();

        when(jwtService.isTokenValid(token, TokenType.REFRESH)).thenReturn(true);
        when(jwtService.extractJti(token)).thenReturn(jti);
        when(refreshTokenRepository.findById(jti)).thenReturn(Optional.of(storedToken));
        when(refreshTokenRepository.saveAndFlush(storedToken))
                .thenThrow(new org.springframework.orm.ObjectOptimisticLockingFailureException(RefreshToken.class, jti));
        when(transactionManager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));

        assertThatThrownBy(() -> refreshTokenService.rotateRefreshToken(token))
                .isInstanceOf(UnauthorizedException.class);

        verify(refreshTokenRepository).revokeAllByUserId(userId);
        verify(userRepository).markSessionsInvalidatedAt(eq(userId), any(LocalDateTime.class));
        verify(jwtService, never()).generateToken(any(), eq(TokenType.ACCESS));
    }

    @Test
    @DisplayName("[invalidateAllSessions] Should Delegate To Repository With Given User Id - When Called")
    void shouldDelegateToRepositoryWithGivenUserIdWhenInvalidateAllSessionsCalled() {
        when(transactionManager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));

        refreshTokenService.invalidateAllSessions(userId);

        verify(refreshTokenRepository).revokeAllByUserId(userId);
        verify(userRepository).markSessionsInvalidatedAt(eq(userId), any(LocalDateTime.class));
    }

    @Test
    @DisplayName("[cleanupExpiredTokens] Should Delegate To Repository With The Current Time - When Called")
    void shouldDelegateToRepositoryWithTheCurrentTimeWhenCleanupExpiredTokensCalled() {
        when(refreshTokenRepository.deleteExpired(any(LocalDateTime.class))).thenReturn(3);

        int result = refreshTokenService.cleanupExpiredTokens();

        assertThat(result).isEqualTo(3);
        ArgumentCaptor<LocalDateTime> nowCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(refreshTokenRepository).deleteExpired(nowCaptor.capture());
        assertThat(nowCaptor.getValue()).isCloseTo(LocalDateTime.now(), within(5, ChronoUnit.SECONDS));
    }

    @Test
    @DisplayName("[revokeRefreshToken] Should Do Nothing - When Token Is Blank")
    void shouldDoNothingWhenTokenIsBlank() {
        refreshTokenService.revokeRefreshToken(" ");

        verifyNoInteractions(refreshTokenRepository);
    }

    @Test
    @DisplayName("[revokeRefreshToken] Should Do Nothing - When Token Cannot Be Parsed")
    void shouldDoNothingWhenTokenCannotBeParsed() {
        String token = "malformed-token";
        when(jwtService.isTokenValid(token, TokenType.REFRESH)).thenReturn(false);

        refreshTokenService.revokeRefreshToken(token);

        verifyNoInteractions(refreshTokenRepository);
    }

    @Test
    @DisplayName("[revokeRefreshToken] Should Do Nothing - When Token Type Is Not Refresh")
    void shouldDoNothingWhenTokenTypeIsNotRefresh() {
        String token = "access-token-value";
        when(jwtService.isTokenValid(token, TokenType.REFRESH)).thenReturn(false);

        refreshTokenService.revokeRefreshToken(token);

        verifyNoInteractions(refreshTokenRepository);
        verify(jwtService, never()).extractJti(token);
    }

    @Test
    @DisplayName("[revokeRefreshToken] Should Do Nothing - When Token Is Not Found In Database")
    void shouldDoNothingWhenTokenIsNotFoundInDatabase() {
        String token = "valid-token";
        UUID jti = UUID.randomUUID();

        when(jwtService.isTokenValid(token, TokenType.REFRESH)).thenReturn(true);
        when(jwtService.extractJti(token)).thenReturn(jti);
        when(refreshTokenRepository.findById(jti)).thenReturn(Optional.empty());

        refreshTokenService.revokeRefreshToken(token);

        verify(refreshTokenRepository, never()).save(any());
    }

    @Test
    @DisplayName("[revokeRefreshToken] Should Mark Stored Token As Revoked - When Token Is Found In Database")
    void shouldMarkStoredTokenAsRevokedWhenTokenIsFoundInDatabase() {
        String token = "valid-token";
        UUID jti = UUID.randomUUID();
        RefreshToken storedToken = RefreshToken.builder()
                .id(jti)
                .user(user)
                .revoked(false)
                .expiresAt(LocalDateTime.now().plusDays(7))
                .createdAt(LocalDateTime.now())
                .build();

        when(jwtService.isTokenValid(token, TokenType.REFRESH)).thenReturn(true);
        when(jwtService.extractJti(token)).thenReturn(jti);
        when(refreshTokenRepository.findById(jti)).thenReturn(Optional.of(storedToken));

        refreshTokenService.revokeRefreshToken(token);

        assertThat(storedToken.getRevoked()).isTrue();
        verify(refreshTokenRepository).save(storedToken);
    }
}
