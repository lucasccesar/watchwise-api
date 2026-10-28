package com.watchwise.watchwise_api.common.security;

import com.google.api.client.googleapis.auth.oauth2.GoogleIdToken;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdTokenVerifier;
import com.google.api.client.json.gson.GsonFactory;
import com.watchwise.watchwise_api.common.exception.BadGatewayException;
import com.watchwise.watchwise_api.common.exception.UnauthorizedException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.security.GeneralSecurityException;
import java.io.IOException;

@Component
@RequiredArgsConstructor
public class GoogleTokenVerifier {

    private final GoogleIdTokenVerifier googleIdTokenVerifier;

    public String verify(String idToken) {
        GoogleIdToken googleIdToken;
        try {
            googleIdToken = GoogleIdToken.parse(GsonFactory.getDefaultInstance(), idToken);
        } catch (IOException | IllegalArgumentException e) {
            throw new UnauthorizedException("Invalid or expired provider token");
        }

        try {
            if (!googleIdTokenVerifier.verify(googleIdToken)) {
                throw new UnauthorizedException("Invalid or expired provider token");
            }
        } catch (IOException e) {
            throw new BadGatewayException("Google identity provider is temporarily unavailable");
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new UnauthorizedException("Invalid or expired provider token");
        }

        GoogleIdToken.Payload payload = googleIdToken.getPayload();
        if (!Boolean.TRUE.equals(payload.getEmailVerified())) {
            throw new UnauthorizedException("Email not verified by provider");
        }

        return payload.getEmail();
    }
}
