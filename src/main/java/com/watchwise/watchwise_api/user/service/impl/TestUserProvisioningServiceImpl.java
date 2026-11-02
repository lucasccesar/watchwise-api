package com.watchwise.watchwise_api.user.service.impl;

import com.watchwise.watchwise_api.common.exception.BadRequestException;
import com.watchwise.watchwise_api.common.exception.ConflictException;
import com.watchwise.watchwise_api.common.exception.ForbiddenException;
import com.watchwise.watchwise_api.user.dto.AdminTestUserCreatedDTO;
import com.watchwise.watchwise_api.user.dto.AdminTestUserCreationDTO;
import com.watchwise.watchwise_api.user.entity.User;
import com.watchwise.watchwise_api.user.entity.UserRole;
import com.watchwise.watchwise_api.user.repository.PendingRegistrationRepository;
import com.watchwise.watchwise_api.user.repository.UserRepository;
import com.watchwise.watchwise_api.user.service.TestUserProvisioningService;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class TestUserProvisioningServiceImpl implements TestUserProvisioningService {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String TEST_EMAIL_DOMAIN = "watchwise.invalid";
    private static final Pattern TEST_EMAIL_PATTERN =
            Pattern.compile("^test-[0-9a-f]{32}@watchwise\\.invalid$");

    private final UserRepository userRepository;
    private final PendingRegistrationRepository pendingRegistrations;
    private final PasswordEncoder passwordEncoder;
    private final Clock clock;

    @Override
    @Transactional
    public AdminTestUserCreatedDTO createTestUser(UUID adminId, AdminTestUserCreationDTO request) {
        requireAdmin(adminId);
        if (request == null || request.username() == null || request.username().isBlank()) {
            throw new BadRequestException("Username is required");
        }
        if (request.password() == null || request.password().isBlank()) {
            throw new BadRequestException("Password is required");
        }

        String username = request.username().trim();
        if (username.length() < 3 || username.length() > 60 || username.contains("@")) {
            throw new BadRequestException("Username must be 3 to 60 characters and must not contain @");
        }
        LocalDateTime now = LocalDateTime.now(clock);
        String name = request.name() == null || request.name().isBlank()
                ? username
                : request.name().trim();
        if (name.length() > 120) {
            throw new BadRequestException("Name must not exceed 120 characters");
        }

        Optional<User> existing = userRepository.findByUsernameIgnoreCase(username);
        if (existing.isPresent()) {
            User user = existing.get();
            if (isMatchingTestUser(user, request.password(), name)) {
                return toResponse(user, false);
            }
            throw new ConflictException("Username already in use");
        }
        if (pendingRegistrations.existsByUsernameIgnoreCaseAndExpiresAtAfter(username, now)) {
            throw new ConflictException("Username has a pending registration");
        }

        String email = newTestEmail();
        pendingRegistrations.lockEmail(email);
        pendingRegistrations.deleteExpiredForEmail(email, now);
        if (userRepository.findByEmailOrPendingEmailIgnoreCaseForUpdate(email).isPresent()
                || pendingRegistrations.countByEmailIgnoreCaseAndExpiresAtAfter(email, now) > 0) {
            throw new ConflictException("Generated test email is already in use; retry the request");
        }

        User testUser = User.builder()
                .role(UserRole.USER)
                .username(username)
                .name(name)
                .email(email)
                .password(passwordEncoder.encode(request.password()))
                .isProfilePublic(false)
                .isEmailVerified(true)
                .createdAt(now)
                .updatedAt(now)
                .build();

        User saved;
        try {
            saved = userRepository.saveAndFlush(testUser);
        } catch (DataIntegrityViolationException exception) {
            throw new ConflictException("Username or generated test email already in use");
        }

        return toResponse(saved, true);
    }

    private void requireAdmin(UUID adminId) {
        User actor = userRepository.findById(adminId)
                .orElseThrow(() -> new ForbiddenException("Administrator access required"));
        if (actor.getRole() != UserRole.ADMIN) {
            throw new ForbiddenException("Administrator access required");
        }
    }

    private boolean isMatchingTestUser(User user, String password, String name) {
        String email = user.getEmail().toLowerCase(Locale.ROOT);
        return user.getRole() == UserRole.USER
                && Boolean.FALSE.equals(user.getIsProfilePublic())
                && Boolean.TRUE.equals(user.getIsEmailVerified())
                && user.getName().equals(name)
                && TEST_EMAIL_PATTERN.matcher(email).matches()
                && passwordEncoder.matches(password, user.getPassword());
    }

    private String newTestEmail() {
        byte[] entropy = new byte[16];
        RANDOM.nextBytes(entropy);
        String token = java.util.HexFormat.of().formatHex(entropy);
        return "test-" + token + "@" + TEST_EMAIL_DOMAIN;
    }

    private AdminTestUserCreatedDTO toResponse(User user, boolean created) {
        return new AdminTestUserCreatedDTO(user.getId(), user.getUsername(), user.getEmail(), created);
    }
}
