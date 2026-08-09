package com.watchwise.watchwise_api.user.controller;

import com.watchwise.watchwise_api.auth.dto.RefreshedTokens;
import com.watchwise.watchwise_api.auth.service.RefreshTokenService;
import com.watchwise.watchwise_api.common.exception.ConflictException;
import com.watchwise.watchwise_api.common.security.CookieUtil;
import com.watchwise.watchwise_api.common.security.JwtService;
import com.watchwise.watchwise_api.common.security.TokenType;
import com.watchwise.watchwise_api.user.dto.LoginUserDTO;
import com.watchwise.watchwise_api.user.dto.PostUserDTO;
import com.watchwise.watchwise_api.user.dto.UserResponseDTO;
import com.watchwise.watchwise_api.user.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.csrf.CsrfAuthenticationStrategy;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthController {

    private final UserService userService;
    private final JwtService jwtService;
    private final CookieUtil cookieUtil;
    private final RefreshTokenService refreshTokenService;
    private final CsrfAuthenticationStrategy csrfAuthenticationStrategy;

    @PostMapping("/register")
    public ResponseEntity<UserResponseDTO> register(
            @Valid @RequestBody PostUserDTO postUserDTO,
            HttpServletRequest request,
            HttpServletResponse response
    ) {
        if (isAuthenticated()) {
            throw new ConflictException("Already authenticated");
        }

        UserResponseDTO user = userService.saveNewUser(postUserDTO);

        rotateCsrfToken(request, response);
        cookieUtil.addCookie(response, buildAccessTokenCookie(user));
        cookieUtil.addCookie(response, buildRefreshTokenCookie(user));

        return ResponseEntity.status(HttpStatus.CREATED).body(user);
    }

    @PostMapping("/login")
    public ResponseEntity<UserResponseDTO> login(
            @Valid @RequestBody LoginUserDTO loginUserDTO,
            HttpServletRequest request,
            HttpServletResponse response
    ) {
        if (isAuthenticated()) {
            throw new ConflictException("Already authenticated");
        }

        UserResponseDTO user = userService.login(loginUserDTO);

        rotateCsrfToken(request, response);
        cookieUtil.addCookie(response, buildAccessTokenCookie(user));
        cookieUtil.addCookie(response, buildRefreshTokenCookie(user));

        return ResponseEntity.ok(user);
    }

    @PostMapping("/refresh")
    public ResponseEntity<Void> refresh(
            @CookieValue(name = CookieUtil.REFRESH_TOKEN_COOKIE, required = false) String refreshToken,
            HttpServletResponse response
    ) {
        RefreshedTokens tokens = refreshTokenService.rotateRefreshToken(refreshToken);

        cookieUtil.addCookie(response, cookieUtil.buildAccessTokenCookie(tokens.accessToken()));
        cookieUtil.addCookie(response, cookieUtil.buildRefreshTokenCookie(tokens.refreshToken()));

        return ResponseEntity.noContent().build();
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(
            @CookieValue(name = CookieUtil.REFRESH_TOKEN_COOKIE, required = false) String refreshToken,
            HttpServletResponse response
    ) {
        refreshTokenService.revokeRefreshToken(refreshToken);

        cookieUtil.addCookie(response, cookieUtil.clearCookie(CookieUtil.ACCESS_TOKEN_COOKIE, "/"));
        cookieUtil.addCookie(response, cookieUtil.clearCookie(CookieUtil.REFRESH_TOKEN_COOKIE, CookieUtil.REFRESH_TOKEN_PATH));
        cookieUtil.addCookie(response, cookieUtil.clearCookie(CookieUtil.CSRF_TOKEN_COOKIE, "/"));

        return ResponseEntity.noContent().build();
    }

    private boolean isAuthenticated() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null
                && authentication.isAuthenticated()
                && !(authentication instanceof AnonymousAuthenticationToken);
    }

    private void rotateCsrfToken(HttpServletRequest request, HttpServletResponse response) {
        csrfAuthenticationStrategy.onAuthentication(null, request, response);

        CsrfToken csrfToken = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
        if (csrfToken != null) {
            csrfToken.getToken();
        }
    }

    private ResponseCookie buildAccessTokenCookie(UserResponseDTO user) {
        String token = jwtService.generateToken(user.id(), user.email(), TokenType.ACCESS);
        return cookieUtil.buildAccessTokenCookie(token);
    }

    private ResponseCookie buildRefreshTokenCookie(UserResponseDTO user) {
        String token = refreshTokenService.issueRefreshToken(user.id(), user.email());
        return cookieUtil.buildRefreshTokenCookie(token);
    }
}
