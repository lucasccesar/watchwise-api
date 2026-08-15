package com.watchwise.watchwise_api.common.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseCookie;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class CookieUtilTest {

    private final CookieUtil cookieUtil = new CookieUtil(60, 7, true, "/api/v1");

    @Test
    @DisplayName("[buildAccessTokenCookie] Should Build HttpOnly Cookie Scoped To Root Path - When Called")
    void shouldBuildHttpOnlyCookieScopedToRootPathWhenCalled() {
        ResponseCookie cookie = cookieUtil.buildAccessTokenCookie("token-value");

        assertThat(cookie.getName()).isEqualTo(CookieUtil.ACCESS_TOKEN_COOKIE);
        assertThat(cookie.getValue()).isEqualTo("token-value");
        assertThat(cookie.isHttpOnly()).isTrue();
        assertThat(cookie.isSecure()).isTrue();
        assertThat(cookie.getPath()).isEqualTo("/");
        assertThat(cookie.getMaxAge()).isEqualTo(Duration.ofMinutes(60));
    }

    @Test
    @DisplayName("[buildRefreshTokenCookie] Should Build Cookie Scoped To Refresh Path Prefixed With Context Path - When Called")
    void shouldBuildCookieScopedToRefreshPathPrefixedWithContextPathWhenCalled() {
        ResponseCookie cookie = cookieUtil.buildRefreshTokenCookie("refresh-token-value");

        assertThat(cookie.getName()).isEqualTo(CookieUtil.REFRESH_TOKEN_COOKIE);
        assertThat(cookie.getValue()).isEqualTo("refresh-token-value");
        assertThat(cookie.isHttpOnly()).isTrue();
        assertThat(cookie.getPath()).isEqualTo("/api/v1/auth/refresh");
        assertThat(cookie.getMaxAge()).isEqualTo(Duration.ofDays(7));
    }

    @Test
    @DisplayName("[buildRefreshTokenCookie] Should Build Cookie Scoped To Bare Refresh Path - When Context Path Is Blank")
    void shouldBuildCookieScopedToBareRefreshPathWhenContextPathIsBlank() {
        CookieUtil noContextPathCookieUtil = new CookieUtil(60, 7, true, "");

        ResponseCookie cookie = noContextPathCookieUtil.buildRefreshTokenCookie("refresh-token-value");

        assertThat(cookie.getPath()).isEqualTo("/auth/refresh");
    }

    @Test
    @DisplayName("[clearCookie] Should Build Empty Cookie With Zero MaxAge - When Called With Name And Path")
    void shouldBuildEmptyCookieWithZeroMaxAgeWhenCalledWithNameAndPath() {
        ResponseCookie cookie = cookieUtil.clearCookie(CookieUtil.CSRF_TOKEN_COOKIE, "/");

        assertThat(cookie.getName()).isEqualTo(CookieUtil.CSRF_TOKEN_COOKIE);
        assertThat(cookie.getValue()).isEmpty();
        assertThat(cookie.getPath()).isEqualTo("/");
        assertThat(cookie.getMaxAge()).isEqualTo(Duration.ZERO);
    }
}
