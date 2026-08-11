package com.watchwise.watchwise_api.common.security;

import com.google.api.client.googleapis.auth.oauth2.GoogleIdToken;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdTokenVerifier;
import com.google.api.client.json.webtoken.JsonWebSignature;
import com.watchwise.watchwise_api.common.exception.UnauthorizedException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.security.GeneralSecurityException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GoogleTokenVerifierTest {

    @Mock
    private GoogleIdTokenVerifier googleIdTokenVerifier;

    @InjectMocks
    private GoogleTokenVerifier googleTokenVerifier;

    private GoogleIdToken buildIdToken(String email, Boolean emailVerified) {
        GoogleIdToken.Payload payload = new GoogleIdToken.Payload()
                .setEmail(email)
                .setEmailVerified(emailVerified);
        return new GoogleIdToken(new JsonWebSignature.Header(), payload, new byte[0], new byte[0]);
    }

    @Test
    @DisplayName("[verify] Should Return Email - When Token Is Valid And Email Is Verified")
    void shouldReturnEmailWhenTokenIsValidAndEmailIsVerified() throws GeneralSecurityException, IOException {
        when(googleIdTokenVerifier.verify("valid-token")).thenReturn(buildIdToken("john.doe@email.com", true));

        String result = googleTokenVerifier.verify("valid-token");

        assertThat(result).isEqualTo("john.doe@email.com");
    }

    @Test
    @DisplayName("[verify] Should Throw UnauthorizedException - When Token Is Invalid Or Expired")
    void shouldThrowUnauthorizedExceptionWhenTokenIsInvalidOrExpired() throws GeneralSecurityException, IOException {
        when(googleIdTokenVerifier.verify("invalid-token")).thenReturn(null);

        assertThatThrownBy(() -> googleTokenVerifier.verify("invalid-token"))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessage("Invalid or expired provider token");
    }

    @Test
    @DisplayName("[verify] Should Throw UnauthorizedException - When Verifier Throws GeneralSecurityException")
    void shouldThrowUnauthorizedExceptionWhenVerifierThrowsGeneralSecurityException() throws GeneralSecurityException, IOException {
        when(googleIdTokenVerifier.verify("bad-token")).thenThrow(new GeneralSecurityException("bad signature"));

        assertThatThrownBy(() -> googleTokenVerifier.verify("bad-token"))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessage("Invalid or expired provider token");
    }

    @Test
    @DisplayName("[verify] Should Throw UnauthorizedException - When Verifier Throws IOException")
    void shouldThrowUnauthorizedExceptionWhenVerifierThrowsIOException() throws GeneralSecurityException, IOException {
        when(googleIdTokenVerifier.verify("network-error-token")).thenThrow(new IOException("network error"));

        assertThatThrownBy(() -> googleTokenVerifier.verify("network-error-token"))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessage("Invalid or expired provider token");
    }

    @Test
    @DisplayName("[verify] Should Throw UnauthorizedException - When Token Is Malformed")
    void shouldThrowUnauthorizedExceptionWhenTokenIsMalformed() throws GeneralSecurityException, IOException {
        when(googleIdTokenVerifier.verify("malformed-token")).thenThrow(new IllegalArgumentException("malformed jwt"));

        assertThatThrownBy(() -> googleTokenVerifier.verify("malformed-token"))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessage("Invalid or expired provider token");
    }

    @Test
    @DisplayName("[verify] Should Throw UnauthorizedException - When Email Is Not Verified")
    void shouldThrowUnauthorizedExceptionWhenEmailIsNotVerified() throws GeneralSecurityException, IOException {
        when(googleIdTokenVerifier.verify("unverified-email-token")).thenReturn(buildIdToken("john.doe@email.com", false));

        assertThatThrownBy(() -> googleTokenVerifier.verify("unverified-email-token"))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessage("Email not verified by provider");
    }

    @Test
    @DisplayName("[verify] Should Throw UnauthorizedException - When EmailVerified Claim Is Missing")
    void shouldThrowUnauthorizedExceptionWhenEmailVerifiedClaimIsMissing() throws GeneralSecurityException, IOException {
        when(googleIdTokenVerifier.verify("missing-claim-token")).thenReturn(buildIdToken("john.doe@email.com", null));

        assertThatThrownBy(() -> googleTokenVerifier.verify("missing-claim-token"))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessage("Email not verified by provider");
    }
}