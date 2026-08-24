package com.watchwise.watchwise_api.user.service.impl;

import com.watchwise.watchwise_api.common.exception.BadRequestException;
import com.watchwise.watchwise_api.common.exception.ConflictException;
import com.watchwise.watchwise_api.common.exception.ForbiddenException;
import com.watchwise.watchwise_api.common.exception.NotFoundException;
import com.watchwise.watchwise_api.auth.service.RefreshTokenService;
import com.watchwise.watchwise_api.common.exception.UnauthorizedException;
import com.watchwise.watchwise_api.follower.service.FollowerService;
import com.watchwise.watchwise_api.user.dto.DeleteAccountDTO;
import com.watchwise.watchwise_api.user.dto.LoginUserDTO;
import com.watchwise.watchwise_api.user.dto.PatchUserDTO;
import com.watchwise.watchwise_api.user.dto.PostUserDTO;
import com.watchwise.watchwise_api.user.dto.PublicUserDTO;
import com.watchwise.watchwise_api.user.dto.UserPreviewDTO;
import com.watchwise.watchwise_api.user.dto.UserResponseDTO;
import com.watchwise.watchwise_api.user.entity.User;
import com.watchwise.watchwise_api.user.mapper.UserMapper;
import com.watchwise.watchwise_api.user.repository.UserRepository;
import com.watchwise.watchwise_api.user.service.UserService;
import io.micrometer.common.util.StringUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {

    private final UserMapper userMapper;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final RefreshTokenService refreshTokenService;
    private final FollowerService followerService;

    static final int DEFAULT_PAGE = 0;
    static final int DEFAULT_PAGE_SIZE = 20;
    static final int MIN_USERNAME_LENGTH = 3;

    @Override
    public UserResponseDTO saveNewUser(PostUserDTO postUserDTO) {

        String trimmedUsername = postUserDTO.username().trim();
        validateUsernameLength(trimmedUsername);

        User mapperUser = userMapper.postUserDtoToUser(postUserDTO);
        mapperUser.setPassword(passwordEncoder.encode(postUserDTO.password()));
        mapperUser.setEmail(postUserDTO.email().toLowerCase().trim());
        mapperUser.setUsername(trimmedUsername);
        mapperUser.setIsEmailVerified(true);

        LocalDateTime now = LocalDateTime.now();
        mapperUser.setCreatedAt(now);
        mapperUser.setUpdatedAt(now);

        try {
            return userMapper.userToUserResponseDto(userRepository.save(mapperUser));
        } catch (DataIntegrityViolationException e) {
            throw mapUniqueConstraintViolation(e);
        }
    }

    @Override
    @Transactional
    public UserResponseDTO updateUser(User user, PatchUserDTO patchUserDTO) {
        boolean touchesCredentials = applyPatch(user, patchUserDTO);
        user.setUpdatedAt(LocalDateTime.now());

        UserResponseDTO response;
        try {
            response = userMapper.userToUserResponseDto(userRepository.saveAndFlush(user));
        } catch (DataIntegrityViolationException e) {
            throw mapUniqueConstraintViolation(e);
        }

        if (touchesCredentials) {
            invalidateSessionsAfterCommit(user.getId());
        }

        return response;
    }

    private void invalidateSessionsAfterCommit(UUID userId) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            refreshTokenService.invalidateAllSessions(userId);
            return;
        }

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                refreshTokenService.invalidateAllSessions(userId);
            }
        });
    }

    @Override
    public CredentialCheck checkCredentialChanges(UUID id, PatchUserDTO patchUserDTO) {
        User user = userRepository.findById(id).orElseThrow(() -> new NotFoundException("User not found"));
        boolean touchesCredentials = resolveCredentialChanges(user, patchUserDTO).touchesCredentials();
        return new CredentialCheck(user, touchesCredentials);
    }

    private boolean applyPatch(User user, PatchUserDTO patchUserDTO) {
        CredentialChanges credentialChanges = resolveCredentialChanges(user, patchUserDTO);

        if (credentialChanges.touchesCredentials()) {
            requireCurrentPassword(user, patchUserDTO.currentPassword());
        }

        if (patchUserDTO.username() != null) {
            String newUsername = patchUserDTO.username().trim();
            validateUsernameLength(newUsername);
            if (!newUsername.equals(user.getUsername())) {
                user.setUsername(newUsername);
            }
        }

        if (credentialChanges.changesEmail()) {
            user.setEmail(credentialChanges.newEmail());
        }

        if (credentialChanges.changesPassword()) {
            user.setPassword(passwordEncoder.encode(patchUserDTO.password()));
        }

        if (patchUserDTO.description() != null && !patchUserDTO.description().equals(user.getDescription())) {
            user.setDescription(patchUserDTO.description());
        }

        if (patchUserDTO.profilePicture() != null && !patchUserDTO.profilePicture().equals(user.getProfilePicture())) {
            user.setProfilePicture(patchUserDTO.profilePicture());
        }

        if (patchUserDTO.isProfilePublic() != null && !patchUserDTO.isProfilePublic().equals(user.getIsProfilePublic())) {
            boolean becamePublic = Boolean.TRUE.equals(patchUserDTO.isProfilePublic()) && !Boolean.TRUE.equals(user.getIsProfilePublic());
            user.setIsProfilePublic(patchUserDTO.isProfilePublic());
            if (becamePublic) {
                followerService.acceptAllPendingFollowRequestsFor(user.getId());
            }
        }

        return credentialChanges.touchesCredentials();
    }

    private void validateUsernameLength(String trimmedUsername) {
        if (trimmedUsername.length() < MIN_USERNAME_LENGTH) {
            throw new BadRequestException("Username must be at least " + MIN_USERNAME_LENGTH + " characters long");
        }
    }

    private void requireCurrentPassword(User user, String currentPassword) {
        if (StringUtils.isEmpty(currentPassword)) {
            throw new BadRequestException("currentPassword must be provided to change password or email");
        }
        if (!passwordEncoder.matches(currentPassword, user.getPassword())) {
            throw new UnauthorizedException("Invalid password");
        }
    }

    private CredentialChanges resolveCredentialChanges(User user, PatchUserDTO patchUserDTO) {
        String newEmail = patchUserDTO.email() != null ? patchUserDTO.email().trim().toLowerCase() : null;
        boolean changesEmail = newEmail != null && !newEmail.equals(user.getEmail());
        boolean changesPassword = patchUserDTO.password() != null
                && !passwordEncoder.matches(patchUserDTO.password(), user.getPassword());
        return new CredentialChanges(newEmail, changesEmail, changesPassword);
    }

    private record CredentialChanges(String newEmail, boolean changesEmail, boolean changesPassword) {
        boolean touchesCredentials() {
            return changesEmail || changesPassword;
        }
    }

    private ConflictException mapUniqueConstraintViolation(DataIntegrityViolationException e) {
        String constraintName = extractConstraintName(e);

        if ("uq_users_username".equals(constraintName)) {
            return new ConflictException("Username already in use");
        }
        if ("uq_users_email".equals(constraintName)) {
            return new ConflictException("Email already in use");
        }
        return new ConflictException("Username or email already in use");
    }

    @Override
    public PublicUserDTO getUserById(UUID id) {
    User foundUser = userRepository.findById(id).orElseThrow(()->new NotFoundException("User not found"));

        if (!Boolean.TRUE.equals(foundUser.getIsProfilePublic())) {
            throw new ForbiddenException("This user profile is private");
        }

        return userMapper.userToPublicUserDto(foundUser);
    }

    @Override
    public UserResponseDTO getCurrentUser(UUID id) {
        User user = userRepository.findById(id).orElseThrow(() -> new NotFoundException("User not found"));

        return userMapper.userToUserResponseDto(user);
    }

    @Override
    public Page<UserPreviewDTO> getUsersByUsername(String username, Integer pageNumber, Integer pageSize) {

        String trimmedUsername = username == null ? null : username.trim();
        if (StringUtils.isEmpty(trimmedUsername)) {
            throw new BadRequestException("Username must be provided");
        }

        PageRequest pageRequest = buildPageRequest(pageNumber, pageSize, null, null);

        return userRepository
                .findByUsernameStartingWithIgnoreCase(trimmedUsername, escapeLikeWildcards(trimmedUsername), pageRequest)
                .map(userMapper::userToUserPreviewDto);
    }

    private String escapeLikeWildcards(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    public PageRequest buildPageRequest(Integer pageNumber, Integer pageSize, String sortBy, String sortDirection) {
        int queryPageNumber;
        int queryPageSize;
        Sort sort = null;

        if (pageNumber != null && pageNumber > 0) {
            queryPageNumber = pageNumber - 1;
        } else if (pageNumber == null || pageNumber == 0) {
            queryPageNumber = DEFAULT_PAGE;
        } else {
            throw new BadRequestException("Page number must be greater than or equal to 0");
        }

        if (pageSize == null || pageSize > 1000) {
            queryPageSize = DEFAULT_PAGE_SIZE;
        } else {
            if (pageSize <= 0) {
                throw new BadRequestException("Page size must be greater than 0");
            } else {
                queryPageSize = pageSize;
            }
        }

        if (!(sortBy == null)) {
            if (sortDirection == null || !sortDirection.equals("desc")) {
                sort = Sort.by(Sort.Order.asc(sortBy));
            } else {
                sort = Sort.by(Sort.Order.desc(sortBy));
            }
        }

        if (sort == null) {
            return PageRequest.of(queryPageNumber, queryPageSize);
        }
        return PageRequest.of(queryPageNumber, queryPageSize, sort);
    }

    @Override
    public UserResponseDTO login(LoginUserDTO loginUserDTO) {
        String identifier = loginUserDTO.identifier().trim();
        User user = userRepository.findByUsernameIgnoreCase(identifier)
                .or(() -> userRepository.findByEmailIgnoreCase(identifier))
                .orElseThrow(() -> new UnauthorizedException("Invalid credentials"));

        if (!passwordEncoder.matches(loginUserDTO.password(), user.getPassword())) {
            throw new UnauthorizedException("Invalid credentials");
        }

        if (!Boolean.TRUE.equals(user.getIsEmailVerified())) {
            throw new ForbiddenException("Email not verified");
        }

        return userMapper.userToUserResponseDto(user);
    }

    @Override
    public Optional<UserResponseDTO> findByEmail(String email) {
        return userRepository.findByEmailIgnoreCase(email.trim()).map(userMapper::userToUserResponseDto);
    }

    @Override
    public void deleteAccount(UUID id, DeleteAccountDTO deleteAccountDTO) {
        User user = userRepository.findById(id).orElseThrow(() -> new NotFoundException("User not found"));

        if (!passwordEncoder.matches(deleteAccountDTO.password(), user.getPassword())) {
            throw new UnauthorizedException("Invalid password");
        }

        userRepository.delete(user);
    }

    private String extractConstraintName(DataIntegrityViolationException e) {
        Throwable cause = e.getCause();
        if (cause instanceof org.hibernate.exception.ConstraintViolationException cve) {
            return cve.getConstraintName();
        }
        return null;
    }
}
