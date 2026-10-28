package com.watchwise.watchwise_api.user.service;

import com.watchwise.watchwise_api.auth.service.RefreshTokenService;
import com.watchwise.watchwise_api.common.exception.BadRequestException;
import com.watchwise.watchwise_api.common.exception.ServiceUnavailableException;
import com.watchwise.watchwise_api.common.security.RequestThrottler;
import com.watchwise.watchwise_api.user.entity.User;
import com.watchwise.watchwise_api.user.repository.UserRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
public class PasswordResetService {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int TOKEN_BYTES = 32;

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final RefreshTokenService refreshTokenService;
    private final ObjectProvider<EmailConfirmationSender> senderProvider;
    private final RequestThrottler requestThrottler;
    private final Clock clock;

    @Value("${app.auth.password-reset.token-ttl-minutes:30}")
    private long tokenTtlMinutes;

    @Value("${app.rate-limit.password-reset-email.max-requests:5}")
    private int maxRequestsPerEmail;

    @Value("${app.rate-limit.password-reset-email.window-minutes:15}")
    private long requestWindowMinutes;

    public PasswordResetService(UserRepository users, PasswordEncoder passwordEncoder,
            RefreshTokenService refreshTokenService, ObjectProvider<EmailConfirmationSender> senderProvider,
            RequestThrottler requestThrottler, Clock clock) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.refreshTokenService = refreshTokenService;
        this.senderProvider = senderProvider;
        this.requestThrottler = requestThrottler;
        this.clock = clock;
    }

    @Transactional
    public void requestPasswordReset(String email) {
        EmailConfirmationSender sender = requireSender();
        String normalizedEmail = email.trim().toLowerCase(Locale.ROOT);
        requestThrottler.checkAllowed(
                "password-reset-email|" + hash(normalizedEmail),
                maxRequestsPerEmail,
                Duration.ofMinutes(requestWindowMinutes));

        Optional<User> candidate = users.findByEmailIgnoreCaseForUpdate(normalizedEmail);
        if (candidate.isEmpty() || !Boolean.TRUE.equals(candidate.get().getIsEmailVerified())) {
            return;
        }

        User user = candidate.get();
        String rawToken = newToken();
        LocalDateTime now = LocalDateTime.now(clock);
        user.setPasswordResetTokenHash(hash(rawToken));
        user.setPasswordResetExpiresAt(now.plus(Duration.ofMinutes(tokenTtlMinutes)));
        users.saveAndFlush(user);
        sendAfterCommit(sender, normalizedEmail, rawToken);
    }

    @Transactional
    public void resetPassword(String rawToken, String newPassword) {
        if (rawToken == null || rawToken.isBlank()) {
            throw invalidResetToken();
        }
        String tokenHash = hash(rawToken);
        User user = users.findByPasswordResetTokenHashForUpdate(tokenHash)
                .orElseThrow(this::invalidResetToken);
        LocalDateTime now = LocalDateTime.now(clock);
        if (user.getPasswordResetExpiresAt() == null || !user.getPasswordResetExpiresAt().isAfter(now)) {
            throw invalidResetToken();
        }

        user.setPassword(passwordEncoder.encode(newPassword));
        user.setPasswordResetTokenHash(null);
        user.setPasswordResetExpiresAt(null);
        user.setAuthVersion(user.getAuthVersion() + 1);
        user.setSessionsInvalidatedAt(now.truncatedTo(ChronoUnit.SECONDS));
        user.setUpdatedAt(now);
        users.saveAndFlush(user);
        revokeRefreshTokensAfterCommit(user.getId());
    }

    private EmailConfirmationSender requireSender() {
        EmailConfirmationSender sender = senderProvider.getIfAvailable();
        if (sender == null || !sender.isConfigured()) {
            throw new ServiceUnavailableException("Email delivery is not configured");
        }
        return sender;
    }

    private void sendAfterCommit(EmailConfirmationSender sender, String email, String rawToken) {
        Runnable delivery = () -> {
            try {
                sender.sendPasswordReset(email, rawToken);
            } catch (IOException exception) {
                // Keep the request response independent of account existence and do not log the token or address.
                log.warn("Password reset email delivery failed ({})", exception.getClass().getSimpleName());
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    delivery.run();
                }
            });
        } else {
            delivery.run();
        }
    }

    private void revokeRefreshTokensAfterCommit(UUID userId) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            refreshTokenService.revokeAllRefreshTokens(userId);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                refreshTokenService.revokeAllRefreshTokens(userId);
            }
        });
    }

    private BadRequestException invalidResetToken() {
        return new BadRequestException("Invalid or expired password reset token");
    }

    private String newToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String hash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }
}
