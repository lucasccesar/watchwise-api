package com.watchwise.watchwise_api.user.service.impl;

import com.watchwise.watchwise_api.common.dto.GenreCountDTO;
import com.watchwise.watchwise_api.common.exception.BadRequestException;
import com.watchwise.watchwise_api.common.exception.ConflictException;
import com.watchwise.watchwise_api.common.exception.ForbiddenException;
import com.watchwise.watchwise_api.common.exception.NotFoundException;
import com.watchwise.watchwise_api.auth.service.RefreshTokenService;
import com.watchwise.watchwise_api.common.exception.UnauthorizedException;
import com.watchwise.watchwise_api.common.pagination.PageRequestFactory;
import com.watchwise.watchwise_api.diaryentry.repository.DiaryEntryRepository;
import com.watchwise.watchwise_api.follower.entity.FollowStatus;
import com.watchwise.watchwise_api.follower.repository.FollowerRepository;
import com.watchwise.watchwise_api.follower.service.FollowerService;
import com.watchwise.watchwise_api.user.dto.DeleteAccountDTO;
import com.watchwise.watchwise_api.user.dto.LoginUserDTO;
import com.watchwise.watchwise_api.user.dto.PatchUserDTO;
import com.watchwise.watchwise_api.user.dto.PostUserDTO;
import com.watchwise.watchwise_api.user.dto.PublicUserProfileDTO;
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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
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
    private final PageRequestFactory pageRequestFactory;
    private final DiaryEntryRepository diaryEntryRepository;
    private final FollowerRepository followerRepository;

    static final int MIN_USERNAME_LENGTH = 3;
    static final int WATCH_TIME_WINDOW_DAYS = 30;

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
            User saved = userRepository.save(mapperUser);
            return toUserResponseDto(saved, ProfileStats.EMPTY);
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
            User saved = userRepository.saveAndFlush(user);
            response = toUserResponseDto(saved, computeProfileStats(saved.getId()));
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

        if (patchUserDTO.banner() != null && !patchUserDTO.banner().equals(user.getBanner())) {
            user.setBanner(patchUserDTO.banner());
        }

        if (patchUserDTO.preferredLanguage() != null && !patchUserDTO.preferredLanguage().equals(user.getPreferredLanguage())) {
            user.setPreferredLanguage(patchUserDTO.preferredLanguage());
        }

        if (patchUserDTO.preferredRegion() != null && !patchUserDTO.preferredRegion().equals(user.getPreferredRegion())) {
            user.setPreferredRegion(patchUserDTO.preferredRegion());
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
    public PublicUserProfileDTO getUserById(UUID id) {
    User foundUser = userRepository.findById(id).orElseThrow(()->new NotFoundException("User not found"));

        if (!Boolean.TRUE.equals(foundUser.getIsProfilePublic())) {
            throw new ForbiddenException("This user profile is private");
        }

        ProfileStats stats = computeProfileStats(id);
        return userMapper.userToPublicUserProfileDto(foundUser, stats.totalMinutesWatched(), stats.minutesWatchedLast30Days(),
                stats.totalTheaterVisits(), stats.genreCountsMovies(), stats.genreCountsSeries(), stats.followersCount(),
                stats.followingCount());
    }

    @Override
    public UserResponseDTO getCurrentUser(UUID id) {
        User user = userRepository.findById(id).orElseThrow(() -> new NotFoundException("User not found"));

        return toUserResponseDto(user, computeProfileStats(id));
    }

    @Override
    public Page<UserPreviewDTO> getUsersByUsername(String username, Integer pageNumber, Integer pageSize) {

        String trimmedUsername = username == null ? null : username.trim();
        if (StringUtils.isEmpty(trimmedUsername)) {
            throw new BadRequestException("Username must be provided");
        }

        PageRequest pageRequest = pageRequestFactory.build(pageNumber, pageSize, null, null);

        return userRepository
                .findByUsernameStartingWithIgnoreCase(trimmedUsername, escapeLikeWildcards(trimmedUsername), pageRequest)
                .map(userMapper::userToUserPreviewDto);
    }

    private String escapeLikeWildcards(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
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

        return toUserResponseDto(user, computeProfileStats(user.getId()));
    }

    @Override
    public Optional<UserResponseDTO> findByEmail(String email) {
        return userRepository.findByEmailIgnoreCase(email.trim())
                .map(user -> toUserResponseDto(user, computeProfileStats(user.getId())));
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

    private UserResponseDTO toUserResponseDto(User user, ProfileStats stats) {
        return userMapper.userToUserResponseDto(user, stats.totalMinutesWatched(), stats.minutesWatchedLast30Days(),
                stats.totalTheaterVisits(), stats.genreCountsMovies(), stats.genreCountsSeries(),
                stats.followersCount(), stats.followingCount());
    }

    private ProfileStats computeProfileStats(UUID userId) {
        LocalDate windowStart = LocalDate.now().minusDays(WATCH_TIME_WINDOW_DAYS);
        LocalDate windowEnd = LocalDate.now();

        long totalMinutesWatched = diaryEntryRepository.sumRuntimeMinutesByUserId(userId);
        long minutesWatchedLast30Days = diaryEntryRepository
                .sumRuntimeMinutesByUserIdAndWatchedDateBetween(userId, windowStart, windowEnd);
        long totalTheaterVisits = diaryEntryRepository.countByUserIdAndWatchedInTheaterTrue(userId);
        List<GenreCountDTO> genreCountsMovies = toGenreCountDtos(
                diaryEntryRepository.countEntriesByGenreAndUserIdForMovies(userId));
        List<GenreCountDTO> genreCountsSeries = toGenreCountDtos(
                diaryEntryRepository.countDistinctTitlesByGenreAndUserIdForSeries(userId));
        long followersCount = followerRepository.countByFollowedIdAndStatus(userId, FollowStatus.ACCEPTED);
        long followingCount = followerRepository.countByFollowerIdAndStatus(userId, FollowStatus.ACCEPTED);

        return new ProfileStats(totalMinutesWatched, minutesWatchedLast30Days, totalTheaterVisits, genreCountsMovies,
                genreCountsSeries, followersCount, followingCount);
    }

    private List<GenreCountDTO> toGenreCountDtos(List<DiaryEntryRepository.GenreCount> rows) {
        return rows.stream()
                .map(row -> new GenreCountDTO(row.getGenre(), row.getCount()))
                .toList();
    }

    private record ProfileStats(long totalMinutesWatched, long minutesWatchedLast30Days, long totalTheaterVisits,
            List<GenreCountDTO> genreCountsMovies, List<GenreCountDTO> genreCountsSeries, long followersCount,
            long followingCount) {
        static final ProfileStats EMPTY = new ProfileStats(0L, 0L, 0L, List.of(), List.of(), 0L, 0L);
    }
}
