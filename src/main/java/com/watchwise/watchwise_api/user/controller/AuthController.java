package com.watchwise.watchwise_api.user.controller;

import com.watchwise.watchwise_api.common.security.CookieUtil;
import com.watchwise.watchwise_api.common.security.JwtService;
import com.watchwise.watchwise_api.common.security.TokenType;
import com.watchwise.watchwise_api.user.dto.LoginUserDTO;
import com.watchwise.watchwise_api.user.dto.PostUserDTO;
import com.watchwise.watchwise_api.user.dto.UserResponseDTO;
import com.watchwise.watchwise_api.user.service.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
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

@PostMapping("/register")
    public ResponseEntity<UserResponseDTO> register(@Valid @RequestBody PostUserDTO postUserDTO) {
        UserResponseDTO user = userService.saveNewUser(postUserDTO);

        return ResponseEntity.status(HttpStatus.CREATED)
                .header(HttpHeaders.SET_COOKIE, buildAccessTokenCookie(user).toString())
                .body(user);
    }

    @PostMapping("/login")
    public ResponseEntity<UserResponseDTO> login(@Valid @RequestBody LoginUserDTO loginUserDTO) {
        UserResponseDTO user = userService.login(loginUserDTO);

        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, buildAccessTokenCookie(user).toString())
                .body(user);
    }

    private ResponseCookie buildAccessTokenCookie(UserResponseDTO user) {
        String token = jwtService.generateToken(user.id(), user.email(), TokenType.ACCESS);
        return cookieUtil.buildAccessTokenCookie(token);
    }
}