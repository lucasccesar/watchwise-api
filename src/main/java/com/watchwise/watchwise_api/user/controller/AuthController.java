package com.watchwise.watchwise_api.user.controller;

import com.watchwise.watchwise_api.auth.dto.RefreshedTokens;
import com.watchwise.watchwise_api.auth.service.RefreshTokenService;
import com.watchwise.watchwise_api.common.exception.ConflictException;
import com.watchwise.watchwise_api.common.exception.UnauthorizedException;
import com.watchwise.watchwise_api.common.security.AttemptLockout;
import com.watchwise.watchwise_api.common.security.CookieUtil;
import com.watchwise.watchwise_api.common.security.GoogleTokenVerifier;
import com.watchwise.watchwise_api.common.security.JwtService;
import com.watchwise.watchwise_api.common.security.OAuthProvider;
import com.watchwise.watchwise_api.common.security.RequestThrottler;
import com.watchwise.watchwise_api.common.security.TokenType;
import com.watchwise.watchwise_api.user.dto.LoginUserDTO;
import com.watchwise.watchwise_api.user.dto.OAuthAccountNotFoundDTO;
import com.watchwise.watchwise_api.user.dto.OAuthLoginDTO;
import com.watchwise.watchwise_api.user.dto.PostUserDTO;
import com.watchwise.watchwise_api.user.dto.UserResponseDTO;
import com.watchwise.watchwise_api.user.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.csrf.CsrfAuthenticationStrategy;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthController {

    private final UserService userService;
    private final JwtService jwtService;
    private final CookieUtil cookieUtil;
    private final RefreshTokenService refreshTokenService;
    private final CsrfAuthenticationStrategy csrfAuthenticationStrategy;
    private final GoogleTokenVerifier googleTokenVerifier;
    private final AttemptLockout attemptLockout;
    private final RequestThrottler requestThrottler;

    @Value("${app.rate-limit.login.max-attempts}")
    private int loginMaxAttempts;
    @Value("${app.rate-limit.login.window-minutes}")
    private long loginWindowMinutes;
    @Value("${app.rate-limit.login.block-minutes}")
    private long loginBlockMinutes;

    @Value("${app.rate-limit.login-ip.max-requests}")
    private int loginIpMaxRequests;
    @Value("${app.rate-limit.login-ip.window-minutes}")
    private long loginIpWindowMinutes;

    @Value("${app.rate-limit.register.max-requests}")
    private int registerMaxRequests;
    @Value("${app.rate-limit.register.window-minutes}")
    private long registerWindowMinutes;

    @Value("${app.rate-limit.oauth.max-requests}")
    private int oauthMaxRequests;
    @Value("${app.rate-limit.oauth.window-minutes}")
    private long oauthWindowMinutes;

    @Value("${app.rate-limit.refresh.max-requests}")
    private int refreshMaxRequests;
    @Value("${app.rate-limit.refresh.window-minutes}")
    private long refreshWindowMinutes;

    @PostMapping("/register")
    public ResponseEntity<UserResponseDTO> register(
            @Valid @RequestBody PostUserDTO postUserDTO,
            HttpServletRequest request,
            HttpServletResponse response
    ) {
        if (isAuthenticated()) {
            throw new ConflictException("Already authenticated");
        }

        requestThrottler.checkAllowed(throttleKey("register", request), registerMaxRequests, Duration.ofMinutes(registerWindowMinutes));

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

        requestThrottler.checkAllowed(throttleKey("login", request), loginIpMaxRequests, Duration.ofMinutes(loginIpWindowMinutes));

        String lockoutKey = buildLockoutKey(request, loginUserDTO.identifier());
        attemptLockout.checkAllowed(lockoutKey, loginMaxAttempts, Duration.ofMinutes(loginWindowMinutes));

        UserResponseDTO user;
        try {
            user = userService.login(loginUserDTO);
        } catch (UnauthorizedException e) {
            attemptLockout.recordFailure(lockoutKey, loginMaxAttempts, Duration.ofMinutes(loginBlockMinutes));
            throw e;
        }
        attemptLockout.recordSuccess(lockoutKey);

        rotateCsrfToken(request, response);
        cookieUtil.addCookie(response, buildAccessTokenCookie(user));
        cookieUtil.addCookie(response, buildRefreshTokenCookie(user));

        return ResponseEntity.ok(user);
    }

    @PostMapping("/oauth/{provider}")
    public ResponseEntity<Object> oauthLogin(
            @PathVariable OAuthProvider provider,
            @Valid @RequestBody OAuthLoginDTO oAuthLoginDTO,
            HttpServletRequest request,
            HttpServletResponse response
    ) {
        if (isAuthenticated()) {
            throw new ConflictException("Already authenticated");
        }

        requestThrottler.checkAllowed(throttleKey("oauth", request), oauthMaxRequests, Duration.ofMinutes(oauthWindowMinutes));

        String verifiedEmail = switch (provider) {
            case GOOGLE -> googleTokenVerifier.verify(oAuthLoginDTO.token());
        };

        Optional<UserResponseDTO> existingUser = userService.findByEmail(verifiedEmail);
        if (existingUser.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new OAuthAccountNotFoundDTO(verifiedEmail));
        }

        UserResponseDTO user = existingUser.get();

        rotateCsrfToken(request, response);
        cookieUtil.addCookie(response, buildAccessTokenCookie(user));
        cookieUtil.addCookie(response, buildRefreshTokenCookie(user));

        return ResponseEntity.ok(user);
    }

    @PostMapping("/refresh")
    public ResponseEntity<Void> refresh(
            @CookieValue(name = CookieUtil.REFRESH_TOKEN_COOKIE, required = false) String refreshToken,
            HttpServletRequest request,
            HttpServletResponse response
    ) {
        requestThrottler.checkAllowed(throttleKey("refresh", request), refreshMaxRequests, Duration.ofMinutes(refreshWindowMinutes));

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
        cookieUtil.addCookie(response, cookieUtil.clearCookie(CookieUtil.REFRESH_TOKEN_COOKIE, cookieUtil.getRefreshTokenPath()));
        cookieUtil.addCookie(response, cookieUtil.clearCookie(CookieUtil.CSRF_TOKEN_COOKIE, cookieUtil.getCsrfTokenPath()));

        return ResponseEntity.noContent().build();
    }

    @PostMapping("/logout-all")
    public ResponseEntity<Void> logoutAll(HttpServletResponse response) {
        refreshTokenService.invalidateAllSessions(getCurrentUserId());

        cookieUtil.addCookie(response, cookieUtil.clearCookie(CookieUtil.ACCESS_TOKEN_COOKIE, "/"));
        cookieUtil.addCookie(response, cookieUtil.clearCookie(CookieUtil.REFRESH_TOKEN_COOKIE, cookieUtil.getRefreshTokenPath()));
        cookieUtil.addCookie(response, cookieUtil.clearCookie(CookieUtil.CSRF_TOKEN_COOKIE, cookieUtil.getCsrfTokenPath()));

        return ResponseEntity.noContent().build();
    }

    private String buildLockoutKey(HttpServletRequest request, String identifier) {
        return "login|" + request.getRemoteAddr() + "|" + identifier.trim().toLowerCase();
    }

    private String throttleKey(String action, HttpServletRequest request) {
        return action + "|" + request.getRemoteAddr();
    }

    private UUID getCurrentUserId() {
        return (UUID) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
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
        String token = jwtService.generateToken(user.id(), TokenType.ACCESS);
        return cookieUtil.buildAccessTokenCookie(token);
    }

    private ResponseCookie buildRefreshTokenCookie(UserResponseDTO user) {
        String token = refreshTokenService.issueRefreshToken(user.id());
        return cookieUtil.buildRefreshTokenCookie(token);
    }
}
