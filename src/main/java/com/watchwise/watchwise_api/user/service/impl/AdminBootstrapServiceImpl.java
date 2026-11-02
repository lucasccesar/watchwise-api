package com.watchwise.watchwise_api.user.service.impl;

import com.watchwise.watchwise_api.common.exception.BadRequestException;
import com.watchwise.watchwise_api.common.exception.ConflictException;
import com.watchwise.watchwise_api.common.exception.NotFoundException;
import com.watchwise.watchwise_api.common.exception.UnauthorizedException;
import com.watchwise.watchwise_api.common.transaction.AdvisoryLock;
import com.watchwise.watchwise_api.user.dto.AdminBootstrapRequestDTO;
import com.watchwise.watchwise_api.user.dto.AdminBootstrapResponseDTO;
import com.watchwise.watchwise_api.user.entity.User;
import com.watchwise.watchwise_api.user.entity.UserRole;
import com.watchwise.watchwise_api.user.repository.UserRepository;
import com.watchwise.watchwise_api.user.service.AdminBootstrapService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class AdminBootstrapServiceImpl implements AdminBootstrapService {

    private static final String BOOTSTRAP_LOCK = "admin-bootstrap:first-admin";

    private final UserRepository userRepository;
    private final AdvisoryLock advisoryLock;
    private final Clock clock;

    @Value("${app.auth.admin-bootstrap.token:}")
    private String configuredToken;

    @Override
    @Transactional
    public AdminBootstrapResponseDTO promoteFirstAdmin(String suppliedToken, AdminBootstrapRequestDTO request) {
        requireValidToken(suppliedToken);
        advisoryLock.lock(BOOTSTRAP_LOCK);

        if (userRepository.existsByRole(UserRole.ADMIN)) {
            throw new ConflictException("Administrator bootstrap has already been completed");
        }

        if (request == null || request.username() == null || request.username().isBlank()) {
            throw new BadRequestException("Username is required");
        }

        String username = request.username().trim();
        User user = userRepository.findByUsernameIgnoreCase(username)
                .orElseThrow(() -> new NotFoundException("User not found"));
        if (user.getRole() != UserRole.USER) {
            throw new ConflictException("Only a regular user can be promoted");
        }
        if (!Boolean.TRUE.equals(user.getIsEmailVerified())) {
            throw new ConflictException("The user's email must be verified before promotion");
        }

        user.setRole(UserRole.ADMIN);
        user.setUpdatedAt(LocalDateTime.now(clock));
        return new AdminBootstrapResponseDTO(user.getId(), user.getUsername(), user.getRole());
    }

    private void requireValidToken(String suppliedToken) {
        if (configuredToken == null || configuredToken.length() < 32
                || suppliedToken == null
                || !MessageDigest.isEqual(
                        configuredToken.getBytes(StandardCharsets.UTF_8),
                        suppliedToken.getBytes(StandardCharsets.UTF_8))) {
            throw new UnauthorizedException("Invalid admin bootstrap token");
        }
    }
}
