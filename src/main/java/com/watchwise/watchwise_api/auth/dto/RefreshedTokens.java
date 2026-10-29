package com.watchwise.watchwise_api.auth.dto;

public record RefreshedTokens(String accessToken, String refreshToken, boolean rememberMe) {

    public RefreshedTokens(String accessToken, String refreshToken) {
        this(accessToken, refreshToken, false);
    }
}
