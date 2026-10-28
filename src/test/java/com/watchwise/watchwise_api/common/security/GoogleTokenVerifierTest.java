package com.watchwise.watchwise_api.common.security;

import com.google.api.client.googleapis.auth.oauth2.GoogleIdToken;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdTokenVerifier;
import com.google.api.client.json.webtoken.JsonWebSignature;
import com.watchwise.watchwise_api.common.exception.BadGatewayException;
import com.watchwise.watchwise_api.common.exception.UnauthorizedException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GoogleTokenVerifierTest {

    @Mock
    private GoogleIdTokenVerifier googleIdTokenVerifier;

    @InjectMocks
    private GoogleTokenVerifier googleTokenVerifier;

    private String token(String email, boolean emailVerified) {
        String header = encode("{\"alg\":\"RS256\",\"kid\":\"test-key\"}");
        String payload = encode("{\"email\":\"" + email + "\",\"email_verified\":" + emailVerified + "}");
        String signature = encode("signature");
        return header + "." + payload + "." + signature;
    }

    private String encode(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("[verify] Should Return Email - When Token Is Valid And Email Is Verified")
    void shouldReturnEmailWhenTokenIsValidAndEmailIsVerified() throws GeneralSecurityException, IOException {
        String token = token("john.doe@email.com", true);
        when(googleIdTokenVerifier.verify(any(GoogleIdToken.class))).thenReturn(true);

        String result = googleTokenVerifier.verify(token);

        assertThat(result).isEqualTo("john.doe@email.com");
    }

    @Test
    @DisplayName("[verify] Should Throw UnauthorizedException - When Token Is Invalid Or Expired")
    void shouldThrowUnauthorizedExceptionWhenTokenIsInvalidOrExpired() throws GeneralSecurityException, IOException {
        String token = token("john.doe@email.com", true);
        when(googleIdTokenVerifier.verify(any(GoogleIdToken.class))).thenReturn(false);

        assertThatThrownBy(() -> googleTokenVerifier.verify(token))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessage("Invalid or expired provider token");
    }

    @Test
    @DisplayName("[verify] Should Throw UnauthorizedException - When Verifier Throws GeneralSecurityException")
    void shouldThrowUnauthorizedExceptionWhenVerifierThrowsGeneralSecurityException() throws GeneralSecurityException, IOException {
        String token = token("john.doe@email.com", true);
        when(googleIdTokenVerifier.verify(any(GoogleIdToken.class)))
                .thenThrow(new GeneralSecurityException("bad signature"));

        assertThatThrownBy(() -> googleTokenVerifier.verify(token))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessage("Invalid or expired provider token");
    }

    @Test
    @DisplayName("[verify] Should Throw BadGatewayException - When Public Key Retrieval Fails")
    void shouldThrowBadGatewayExceptionWhenPublicKeyRetrievalFails() throws GeneralSecurityException, IOException {
        String token = token("john.doe@email.com", true);
        when(googleIdTokenVerifier.verify(any(GoogleIdToken.class))).thenThrow(new IOException("network error"));

        assertThatThrownBy(() -> googleTokenVerifier.verify(token))
                .isInstanceOf(BadGatewayException.class)
                .hasMessage("Google identity provider is temporarily unavailable");
    }

    @Test
    @DisplayName("[verify] Should Treat A Parsing IOException As Invalid Input")
    void shouldTreatAParsingIOExceptionAsInvalidInput() throws GeneralSecurityException, IOException {
        lenient().when(googleIdTokenVerifier.verify("malformed-token"))
                .thenThrow(new IOException("malformed jwt"));

        assertThatThrownBy(() -> googleTokenVerifier.verify("malformed-token"))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessage("Invalid or expired provider token");
        verify(googleIdTokenVerifier, org.mockito.Mockito.never()).verify(any(GoogleIdToken.class));
    }

    @Test
    @DisplayName("[verify] Should Throw UnauthorizedException - When Email Is Not Verified")
    void shouldThrowUnauthorizedExceptionWhenEmailIsNotVerified() throws GeneralSecurityException, IOException {
        String token = token("john.doe@email.com", false);
        when(googleIdTokenVerifier.verify(any(GoogleIdToken.class))).thenReturn(true);

        assertThatThrownBy(() -> googleTokenVerifier.verify(token))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessage("Email not verified by provider");
    }

    @Test
    @DisplayName("[verify] Should Throw UnauthorizedException - When EmailVerified Claim Is Missing")
    void shouldThrowUnauthorizedExceptionWhenEmailVerifiedClaimIsMissing() throws GeneralSecurityException, IOException {
        String token = encode("{\"alg\":\"RS256\",\"kid\":\"test-key\"}") + "."
                + encode("{\"email\":\"john.doe@email.com\"}") + "." + encode("signature");
        when(googleIdTokenVerifier.verify(any(GoogleIdToken.class))).thenReturn(true);

        assertThatThrownBy(() -> googleTokenVerifier.verify(token))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessage("Email not verified by provider");
    }
}
