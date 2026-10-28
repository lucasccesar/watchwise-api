package com.watchwise.watchwise_api.user.service;

import com.watchwise.watchwise_api.common.exception.BadRequestException;
import com.watchwise.watchwise_api.common.exception.ServiceUnavailableException;
import com.watchwise.watchwise_api.common.exception.TooManyRequestsException;
import com.watchwise.watchwise_api.user.entity.PendingRegistration;
import com.watchwise.watchwise_api.user.entity.User;
import com.watchwise.watchwise_api.user.repository.PendingRegistrationRepository;
import com.watchwise.watchwise_api.user.repository.UserRepository;
import com.watchwise.watchwise_api.auth.service.RefreshTokenService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class EmailConfirmationServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-27T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private UserRepository users;
    private PendingRegistrationRepository pendingRegistrations;
    private ObjectProvider<EmailConfirmationSender> senderProvider;
    private EmailConfirmationSender sender;
    private RefreshTokenService refreshTokenService;
    private EmailConfirmationService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        users = mock(UserRepository.class);
        pendingRegistrations = mock(PendingRegistrationRepository.class);
        senderProvider = mock(ObjectProvider.class);
        sender = mock(EmailConfirmationSender.class);
        refreshTokenService = mock(RefreshTokenService.class);
        when(senderProvider.getIfAvailable()).thenReturn(sender);
        service = new EmailConfirmationService(users, pendingRegistrations, senderProvider, refreshTokenService, CLOCK);
        ReflectionTestUtils.setField(service, "tokenTtlMinutes", 30L);
        ReflectionTestUtils.setField(service, "maxPendingAttemptsPerEmail", 3);
    }

    @Test
    void emailChangeConfirmationIsSentToPendingAddress() throws Exception {
        UUID userId = UUID.randomUUID();
        User user = User.builder().id(userId).email("current@example.com").pendingEmail("new@example.com")
                .isEmailVerified(true).build();
        when(users.findById(userId)).thenReturn(Optional.of(user));
        when(users.saveAndFlush(user)).thenReturn(user);

        service.issueAndSend(userId);

        verify(sender).sendConfirmation(eq("new@example.com"), any());
        assertThat(user.getEmail()).isEqualTo("current@example.com");
        assertThat(user.getPendingEmail()).isEqualTo("new@example.com");
        assertThat(user.getIsEmailVerified()).isTrue();
    }

    @Test
    void sameEmailCanHaveIndependentRegistrationAttemptsWithHashedSecrets() throws Exception {
        List<PendingRegistration> saved = new ArrayList<>();
        when(pendingRegistrations.saveAndFlush(any(PendingRegistration.class))).thenAnswer(invocation -> {
            PendingRegistration pending = invocation.getArgument(0);
            pending.setId(UUID.randomUUID());
            saved.add(pending);
            return pending;
        });

        String firstProof = service.issueForRegistration(registrationDraft("first"));
        String firstToken = capturedTokenFor("same@example.com");
        String secondProof = service.issueForRegistration(registrationDraft("second"));
        String secondToken = capturedTokenFor("same@example.com");

        assertThat(saved).hasSize(2);
        assertThat(saved).extracting(PendingRegistration::getEmail)
                .containsExactly("same@example.com", "same@example.com");
        assertThat(saved).extracting(PendingRegistration::getUsername).containsExactly("first", "second");
        assertThat(firstProof).isNotEqualTo(secondProof);
        assertThat(saved.get(0).getRegistrationProofHash()).isEqualTo(sha256(firstProof));
        assertThat(saved.get(1).getRegistrationProofHash()).isEqualTo(sha256(secondProof));
        assertThat(saved.get(0).getConfirmationTokenHash()).isEqualTo(sha256(firstToken));
        assertThat(saved.get(1).getConfirmationTokenHash()).isEqualTo(sha256(secondToken));
        assertThat(saved).allSatisfy(pending -> assertThat(pending.getExpiresAt())
                .isEqualTo(LocalDateTime.ofInstant(NOW, ZoneOffset.UTC).plusMinutes(30)));
        verify(users, never()).saveAndFlush(any(User.class));
    }

    @Test
    void registrationAttemptLimitIsEnforcedBeforeAnotherEmailIsSent() throws Exception {
        when(pendingRegistrations.countByEmailIgnoreCaseAndExpiresAtAfter(
                "same@example.com", LocalDateTime.ofInstant(NOW, ZoneOffset.UTC))).thenReturn(3L);

        assertThatThrownBy(() -> service.issueForRegistration(registrationDraft("fourth")))
                .isInstanceOf(TooManyRequestsException.class)
                .hasMessage("Too many pending registration attempts for this email. Try again later.");

        verify(pendingRegistrations, never()).saveAndFlush(any(PendingRegistration.class));
        verify(sender, never()).sendConfirmation(any(), any());
    }

    @Test
    void confirmationCreatesAUserOnlyWhenTheEmailTokenAndMatchingBrowserProofArePresented() throws Exception {
        String token = "registration-confirmation-token";
        String rawProof = "browser-proof";
        PendingRegistration pending = pending(token, rawProof, LocalDateTime.ofInstant(NOW, ZoneOffset.UTC).plusMinutes(1));
        when(pendingRegistrations.findByConfirmationTokenHashForUpdate(sha256(token)))
                .thenReturn(Optional.of(pending));
        when(users.findByEmailOrPendingEmailIgnoreCaseForUpdate("new@example.com"))
                .thenReturn(Optional.empty());
        when(users.findByUsernameIgnoreCase("confirmeduser")).thenReturn(Optional.empty());
        UUID userId = UUID.randomUUID();
        when(users.saveAndFlush(any(User.class))).thenAnswer(invocation -> {
            User confirmed = invocation.getArgument(0);
            confirmed.setId(userId);
            return confirmed;
        });

        EmailConfirmationService.ConfirmationResult result = service.confirm(token, rawProof);

        assertThat(result.userId()).isEqualTo(userId);
        assertThat(result.registrationConfirmation()).isTrue();
        org.mockito.ArgumentCaptor<User> userCaptor = org.mockito.ArgumentCaptor.forClass(User.class);
        verify(users).saveAndFlush(userCaptor.capture());
        assertThat(userCaptor.getValue().getIsEmailVerified()).isTrue();
        assertThat(userCaptor.getValue().getEmail()).isEqualTo("new@example.com");
        assertThat(userCaptor.getValue().getUsername()).isEqualTo("confirmeduser");
        assertThat(userCaptor.getValue().getPassword()).isEqualTo("password-hash");
        verify(pendingRegistrations).deleteByEmailIgnoreCase("new@example.com");
    }

    @Test
    void wrongBrowserProofDoesNotConsumeTheIndependentRegistrationAttempt() throws Exception {
        String token = "registration-confirmation-token";
        PendingRegistration pending = pending(token, "right-proof",
                LocalDateTime.ofInstant(NOW, ZoneOffset.UTC).plusMinutes(1));
        when(pendingRegistrations.findByConfirmationTokenHashForUpdate(sha256(token)))
                .thenReturn(Optional.of(pending));

        assertThatThrownBy(() -> service.confirm(token, "wrong-proof"))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("Invalid or expired email confirmation token");

        verify(users, never()).saveAndFlush(any(User.class));
        verify(pendingRegistrations, never()).deleteByEmailIgnoreCase(any());
    }

    @Test
    void emailChangeConfirmationPromotesPendingAddressAndInvalidatesSessions() throws Exception {
        String token = "email-change-confirmation-token";
        UUID userId = UUID.randomUUID();
        User user = User.builder().id(userId).email("current@example.com").pendingEmail("new@example.com")
                .isEmailVerified(true).emailConfirmationTokenHash(sha256(token))
                .emailConfirmationExpiresAt(LocalDateTime.ofInstant(NOW, ZoneOffset.UTC).plusMinutes(1)).build();
        when(pendingRegistrations.findByConfirmationTokenHashForUpdate(sha256(token))).thenReturn(Optional.empty());
        when(users.findByEmailConfirmationTokenHashForUpdate(sha256(token))).thenReturn(Optional.of(user));
        when(users.saveAndFlush(user)).thenReturn(user);

        TransactionSynchronizationManager.initSynchronization();
        try {
            EmailConfirmationService.ConfirmationResult result = service.confirm(token);

            assertThat(result.registrationConfirmation()).isFalse();
            assertThat(user.getEmail()).isEqualTo("new@example.com");
            assertThat(user.getPendingEmail()).isNull();
            assertThat(user.getIsEmailVerified()).isTrue();
            assertThat(user.getAuthVersion()).isEqualTo(1L);
            assertThat(user.getSessionsInvalidatedAt()).isEqualTo(LocalDateTime.ofInstant(NOW, ZoneOffset.UTC));
            verifyNoInteractions(refreshTokenService);
            TransactionSynchronizationManager.getSynchronizations()
                    .forEach(TransactionSynchronization::afterCommit);
            verify(refreshTokenService).revokeAllRefreshTokens(userId);
            verify(refreshTokenService, never()).invalidateAllSessions(any());
            verify(users).saveAndFlush(user);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void resendRotatesOnlyTheAttemptMatchingTheBrowserProof() throws Exception {
        String browserProof = "right-proof";
        PendingRegistration pending = pending("old-token", browserProof,
                LocalDateTime.ofInstant(NOW, ZoneOffset.UTC).plusMinutes(1));
        when(pendingRegistrations.findByEmailAndRegistrationProofHashForUpdate(
                "new@example.com", sha256(browserProof), LocalDateTime.ofInstant(NOW, ZoneOffset.UTC)))
                .thenReturn(Optional.of(pending));
        when(pendingRegistrations.saveAndFlush(pending)).thenReturn(pending);

        assertThat(service.resend("new@example.com", browserProof)).isTrue();

        org.mockito.ArgumentCaptor<String> tokenCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(sender).sendConfirmation(eq("new@example.com"), tokenCaptor.capture());
        assertThat(pending.getConfirmationTokenHash()).isEqualTo(sha256(tokenCaptor.getValue()));
        assertThat(pending.getConfirmationTokenHash()).isNotEqualTo(sha256("old-token"));
        assertThat(pending.getRegistrationProofHash()).isEqualTo(sha256(browserProof));
        assertThat(pending.getExpiresAt()).isEqualTo(LocalDateTime.ofInstant(NOW, ZoneOffset.UTC).plusMinutes(30));
    }

    @Test
    void resendDoesNotRotateOrSendWhenBrowserProofDoesNotMatch() throws Exception {
        when(pendingRegistrations.findByEmailAndRegistrationProofHashForUpdate(
                eq("new@example.com"), any(), eq(LocalDateTime.ofInstant(NOW, ZoneOffset.UTC))))
                .thenReturn(Optional.empty());

        assertThat(service.resend("new@example.com", "wrong-proof")).isFalse();

        verify(sender, never()).sendConfirmation(any(), any());
        verify(pendingRegistrations, never()).saveAndFlush(any(PendingRegistration.class));
    }

    @Test
    void expiredEmailChangeConfirmationIsRejectedWithoutChangingTheAccount() throws Exception {
        String token = "expired-confirmation-token";
        User user = User.builder().id(UUID.randomUUID()).email("current@example.com").pendingEmail("new@example.com")
                .isEmailVerified(true).emailConfirmationTokenHash(sha256(token))
                .emailConfirmationExpiresAt(LocalDateTime.ofInstant(NOW, ZoneOffset.UTC)).build();
        when(pendingRegistrations.findByConfirmationTokenHashForUpdate(sha256(token))).thenReturn(Optional.empty());
        when(users.findByEmailConfirmationTokenHashForUpdate(sha256(token))).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> service.confirm(token))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("Invalid or expired email confirmation token");

        assertThat(user.getEmail()).isEqualTo("current@example.com");
        assertThat(user.getPendingEmail()).isEqualTo("new@example.com");
        assertThat(user.getIsEmailVerified()).isTrue();
        assertThat(user.getAuthVersion()).isEqualTo(0L);
        verifyNoInteractions(refreshTokenService);
        verify(users, never()).saveAndFlush(any());
    }

    @Test
    void deliveryIsRequiredBeforeAConfirmationCanBeIssued() {
        when(senderProvider.getIfAvailable()).thenReturn(null);

        assertThatThrownBy(service::requireDeliveryConfigured)
                .isInstanceOf(ServiceUnavailableException.class)
                .hasMessage("Email confirmation is not configured");
    }

    private User registrationDraft(String username) {
        return User.builder().username(username).name(username).email("same@example.com")
                .password("password-hash").description("bio").profilePicture("https://default-image.png")
                .isProfilePublic(true).isEmailVerified(false).build();
    }

    private PendingRegistration pending(String token, String proof, LocalDateTime expiresAt) throws Exception {
        return PendingRegistration.builder().id(UUID.randomUUID()).username("confirmeduser").name("Confirmed User")
                .email("new@example.com").passwordHash("password-hash").profilePicture("https://default-image.png")
                .isProfilePublic(true).confirmationTokenHash(sha256(token)).registrationProofHash(sha256(proof))
                .expiresAt(expiresAt).createdAt(LocalDateTime.ofInstant(NOW, ZoneOffset.UTC))
                .updatedAt(LocalDateTime.ofInstant(NOW, ZoneOffset.UTC)).build();
    }

    private String capturedTokenFor(String email) throws Exception {
        org.mockito.ArgumentCaptor<String> tokenCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(sender, org.mockito.Mockito.atLeastOnce()).sendConfirmation(eq(email), tokenCaptor.capture());
        List<String> all = tokenCaptor.getAllValues();
        return all.get(all.size() - 1);
    }

    private static String sha256(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
    }
}
