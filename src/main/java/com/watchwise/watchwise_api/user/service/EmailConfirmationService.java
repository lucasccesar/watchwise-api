package com.watchwise.watchwise_api.user.service;

import com.watchwise.watchwise_api.auth.service.RefreshTokenService;
import com.watchwise.watchwise_api.common.exception.BadGatewayException;
import com.watchwise.watchwise_api.common.exception.BadRequestException;
import com.watchwise.watchwise_api.common.exception.ConflictException;
import com.watchwise.watchwise_api.common.exception.NotFoundException;
import com.watchwise.watchwise_api.common.exception.ServiceUnavailableException;
import com.watchwise.watchwise_api.common.exception.TooManyRequestsException;
import com.watchwise.watchwise_api.user.entity.PendingRegistration;
import com.watchwise.watchwise_api.user.entity.User;
import com.watchwise.watchwise_api.user.entity.UserRole;
import com.watchwise.watchwise_api.user.repository.PendingRegistrationRepository;
import com.watchwise.watchwise_api.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Scheduled;
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
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class EmailConfirmationService {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int TOKEN_BYTES = 32;

    private final UserRepository users;
    private final PendingRegistrationRepository pendingRegistrations;
    private final ObjectProvider<EmailConfirmationSender> senderProvider;
    private final RefreshTokenService refreshTokenService;
    private final Clock clock;

    @Value("${app.auth.email-confirmation.token-ttl-minutes:30}")
    private long tokenTtlMinutes;

    @Value("${app.auth.registration.max-pending-attempts-per-email:3}")
    private int maxPendingAttemptsPerEmail;

    public void requireDeliveryConfigured() {
        if (senderProvider.getIfAvailable() == null) {
            throw new ServiceUnavailableException("Email confirmation is not configured");
        }
    }

    @Transactional
    public void issueAndSend(java.util.UUID userId) {
        User user = users.findById(userId).orElseThrow(() -> new NotFoundException("User not found"));
        issueAndSend(user);
    }

    @Transactional
    public void issueAndSend(User user) {
        EmailConfirmationSender sender = senderProvider.getIfAvailable();
        if (sender == null) {
            throw new ServiceUnavailableException("Email confirmation is not configured");
        }
        if (Boolean.TRUE.equals(user.getIsEmailVerified()) && user.getPendingEmail() == null) {
            throw new BadRequestException("Email is already confirmed");
        }

        String rawToken = newToken();
        LocalDateTime now = LocalDateTime.now(clock);
        user.setEmailConfirmationTokenHash(hash(rawToken));
        user.setEmailConfirmationExpiresAt(now.plus(Duration.ofMinutes(tokenTtlMinutes)));
        users.saveAndFlush(user);
        sendAfterCommit(sender, user.getPendingEmail() == null ? user.getEmail() : user.getPendingEmail(), rawToken);
    }

    @Transactional
    public String issueForRegistration(User registration) {
        EmailConfirmationSender sender = senderProvider.getIfAvailable();
        if (sender == null) {
            throw new ServiceUnavailableException("Email confirmation is not configured");
        }

        String email = registration.getEmail().trim().toLowerCase(java.util.Locale.ROOT);
        LocalDateTime now = LocalDateTime.now(clock);
        pendingRegistrations.lockEmail(email);
        if (users.findByEmailOrPendingEmailIgnoreCaseForUpdate(email).isPresent()) {
            throw new ConflictException("Email already in use");
        }
        pendingRegistrations.deleteExpiredForEmail(email, now);
        if (pendingRegistrations.countByEmailIgnoreCaseAndExpiresAtAfter(email, now)
                >= maxPendingAttemptsPerEmail) {
            throw new TooManyRequestsException(
                    "Too many pending registration attempts for this email. Try again later.");
        }

        String rawToken = newToken();
        String rawRegistrationProof = newToken();
        PendingRegistration pending = PendingRegistration.builder()
                .username(registration.getUsername())
                .name(registration.getName())
                .email(email)
                .passwordHash(registration.getPassword())
                .description(registration.getDescription())
                .profilePicture(registration.getProfilePicture())
                .banner(registration.getBanner())
                .isProfilePublic(registration.getIsProfilePublic())
                .confirmationTokenHash(hash(rawToken))
                .registrationProofHash(hash(rawRegistrationProof))
                .expiresAt(now.plus(Duration.ofMinutes(tokenTtlMinutes)))
                .createdAt(now)
                .updatedAt(now)
                .build();
        PendingRegistration saved = pendingRegistrations.saveAndFlush(pending);
        sendAfterCommit(sender, email, rawToken, saved.getId());
        return rawRegistrationProof;
    }

    @Scheduled(cron = "${app.auth.registration.cleanup.cron:0 */15 * * * *}", zone = "UTC")
    @Transactional
    public void cleanupExpiredRegistrations() {
        pendingRegistrations.deleteExpired(LocalDateTime.now(clock));
    }

    @Transactional
    public ConfirmationResult confirm(String rawToken) {
        return confirm(rawToken, null);
    }

    @Transactional
    public ConfirmationResult confirm(String rawToken, String rawRegistrationProof) {
        String tokenHash = hash(rawToken);
        Optional<String> registrationEmail = pendingRegistrations.findEmailByConfirmationTokenHash(tokenHash);
        if (registrationEmail.isPresent()) {
            lockRegistrationEmail(registrationEmail.get());
        } else {
            users.findPendingEmailByEmailConfirmationTokenHash(tokenHash)
                    .ifPresent(this::lockRegistrationEmail);
        }
        Optional<PendingRegistration> registration =
                pendingRegistrations.findByConfirmationTokenHashForUpdate(tokenHash);
        if (registration.isPresent()) {
            return confirmRegistration(registration.get(), rawRegistrationProof);
        }

        User user = users.findByEmailConfirmationTokenHashForUpdate(tokenHash)
                .orElseThrow(() -> invalidConfirmationToken());
        LocalDateTime now = LocalDateTime.now(clock);
        if (user.getEmailConfirmationExpiresAt() == null || !user.getEmailConfirmationExpiresAt().isAfter(now)) {
            throw invalidConfirmationToken();
        }

        boolean emailChangeConfirmation = user.getPendingEmail() != null;
        if (emailChangeConfirmation) {
            user.setEmail(user.getPendingEmail());
            user.setPendingEmail(null);
            user.setAuthVersion(user.getAuthVersion() + 1);
            user.setSessionsInvalidatedAt(now.truncatedTo(ChronoUnit.SECONDS));
        }
        user.setIsEmailVerified(true);
        user.setEmailConfirmationTokenHash(null);
        user.setEmailConfirmationExpiresAt(null);
        users.saveAndFlush(user);
        if (emailChangeConfirmation) {
            pendingRegistrations.deleteByEmailIgnoreCase(user.getEmail());
            revokeRefreshTokensAfterCommit(user.getId());
        }
        return new ConfirmationResult(user.getId(), false);
    }

    private ConfirmationResult confirmRegistration(PendingRegistration registration, String rawRegistrationProof) {
        LocalDateTime now = LocalDateTime.now(clock);
        if (registration.getExpiresAt() == null || !registration.getExpiresAt().isAfter(now)
                || !matches(rawRegistrationProof, registration.getRegistrationProofHash())) {
            throw invalidConfirmationToken();
        }

        if (users.findByEmailOrPendingEmailIgnoreCaseForUpdate(registration.getEmail()).isPresent()) {
            throw new ConflictException("Email already in use");
        }
        if (users.findByUsernameIgnoreCase(registration.getUsername()).isPresent()) {
            throw new ConflictException("Username already in use");
        }

        User confirmed = User.builder()
                .role(UserRole.USER)
                .username(registration.getUsername())
                .name(registration.getName())
                .email(registration.getEmail())
                .password(registration.getPasswordHash())
                .description(registration.getDescription())
                .profilePicture(registration.getProfilePicture())
                .banner(registration.getBanner())
                .isProfilePublic(registration.getIsProfilePublic())
                .isEmailVerified(true)
                .createdAt(now)
                .updatedAt(now)
                .build();

        try {
            User saved = users.saveAndFlush(confirmed);
            pendingRegistrations.deleteByEmailIgnoreCase(registration.getEmail());
            return new ConfirmationResult(saved.getId(), true);
        } catch (DataIntegrityViolationException exception) {
            throw mapUniqueConstraintViolation(exception);
        }
    }

    @Transactional
    public boolean resend(String email, String rawRegistrationProof) {
        requireDeliveryConfigured();
        EmailConfirmationSender sender = senderProvider.getIfAvailable();
        String normalizedEmail = email.trim().toLowerCase(java.util.Locale.ROOT);
        LocalDateTime now = LocalDateTime.now(clock);
        if (rawRegistrationProof == null || rawRegistrationProof.isBlank()) {
            return false;
        }
        Optional<PendingRegistration> registration = pendingRegistrations
                .findByEmailAndRegistrationProofHashForUpdate(
                        normalizedEmail, hash(rawRegistrationProof), now);
        if (registration.isEmpty()) {
            return false;
        }

        PendingRegistration pending = registration.get();
        String rawToken = newToken();
        pending.setConfirmationTokenHash(hash(rawToken));
        pending.setExpiresAt(now.plus(Duration.ofMinutes(tokenTtlMinutes)));
        pending.setUpdatedAt(now);
        pendingRegistrations.saveAndFlush(pending);
        sendAfterCommit(sender, normalizedEmail, rawToken, pending.getId());
        return true;
    }

    private void sendAfterCommit(EmailConfirmationSender sender, String email, String token) {
        sendAfterCommit(sender, email, token, null);
    }

    private void sendAfterCommit(EmailConfirmationSender sender, String email, String token,
            java.util.UUID pendingRegistrationId) {
        Runnable delivery = () -> {
            try {
                sender.sendConfirmation(email, token);
            } catch (IOException exception) {
                if (pendingRegistrationId != null) {
                    pendingRegistrations.deleteById(pendingRegistrationId);
                }
                throw new BadGatewayException("Email confirmation provider is temporarily unavailable");
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

    private void revokeRefreshTokensAfterCommit(java.util.UUID userId) {
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

    private BadRequestException invalidConfirmationToken() {
        return new BadRequestException("Invalid or expired email confirmation token");
    }

    private ConflictException mapUniqueConstraintViolation(DataIntegrityViolationException exception) {
        Throwable cause = exception.getCause();
        if (cause instanceof ConstraintViolationException violation) {
            if ("uq_users_username".equals(violation.getConstraintName())) {
                return new ConflictException("Username already in use");
            }
            if ("uq_users_email".equals(violation.getConstraintName())
                    || "uq_users_effective_email".equals(violation.getConstraintName())) {
                return new ConflictException("Email already in use");
            }
        }
        return new ConflictException("Username or email already in use");
    }

    public record ConfirmationResult(java.util.UUID userId, boolean registrationConfirmation) {
    }

    private String newToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private void lockRegistrationEmail(String email) {
        pendingRegistrations.lockEmail(email.trim().toLowerCase(java.util.Locale.ROOT));
    }

    private String hash(String rawToken) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(rawToken.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private boolean matches(String rawProof, String expectedHash) {
        if (rawProof == null || rawProof.isBlank() || expectedHash == null) {
            return false;
        }
        byte[] actual = hash(rawProof).getBytes(StandardCharsets.US_ASCII);
        byte[] expected = expectedHash.getBytes(StandardCharsets.US_ASCII);
        return MessageDigest.isEqual(actual, expected);
    }
}
