package com.watchwise.watchwise_api.user.service.impl;

import com.watchwise.watchwise_api.auth.service.RefreshTokenService;
import com.watchwise.watchwise_api.common.exception.BadRequestException;
import com.watchwise.watchwise_api.common.exception.ConflictException;
import com.watchwise.watchwise_api.common.exception.ForbiddenException;
import com.watchwise.watchwise_api.common.exception.NotFoundException;
import com.watchwise.watchwise_api.common.exception.ServiceUnavailableException;
import com.watchwise.watchwise_api.common.exception.UnauthorizedException;
import com.watchwise.watchwise_api.common.dto.GenreCountDTO;
import com.watchwise.watchwise_api.common.pagination.PageRequestFactory;
import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.diaryentry.repository.DiaryEntryRepository;
import com.watchwise.watchwise_api.follower.entity.FollowStatus;
import com.watchwise.watchwise_api.follower.repository.FollowerRepository;
import com.watchwise.watchwise_api.follower.service.FollowerService;
import com.watchwise.watchwise_api.follower.entity.Follower;
import com.watchwise.watchwise_api.user.dto.LoginUserDTO;
import com.watchwise.watchwise_api.user.dto.PatchUserDTO;
import com.watchwise.watchwise_api.user.dto.PostUserDTO;
import com.watchwise.watchwise_api.user.dto.PublicUserProfileDTO;
import com.watchwise.watchwise_api.user.dto.UserPreviewDTO;
import com.watchwise.watchwise_api.user.dto.UserResponseDTO;
import com.watchwise.watchwise_api.user.dto.ViewerProfileRelation;
import com.watchwise.watchwise_api.userblock.entity.UserBlockRelationship;
import com.watchwise.watchwise_api.userblock.service.UserBlockAccessService;
import com.watchwise.watchwise_api.user.entity.User;
import com.watchwise.watchwise_api.user.mapper.UserMapper;
import com.watchwise.watchwise_api.user.repository.UserRepository;
import com.watchwise.watchwise_api.user.service.UserService;
import com.watchwise.watchwise_api.user.service.EmailConfirmationService;
import com.watchwise.watchwise_api.user.service.UserVisibilityService;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UserServiceImplTest {
    @Mock
    private UserMapper userMapper;

    @Mock
    private UserRepository userRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private RefreshTokenService refreshTokenService;

    @Mock
    private FollowerService followerService;

    @Mock
    private DiaryEntryRepository diaryEntryRepository;

    @Mock
    private FollowerRepository followerRepository;

    @Mock
    private UserVisibilityService userVisibilityService;

    @Mock
    private UserBlockAccessService userBlockAccessService;

    @Mock
    private EmailConfirmationService emailConfirmationService;

    @Spy
    private PageRequestFactory pageRequestFactory = new PageRequestFactory();

    @InjectMocks
    private UserServiceImpl userService;

    @Captor
    private ArgumentCaptor<PageRequest> pageRequestCaptor;


    private PostUserDTO postUserDTO;
    private User mappedUser;
    private User savedUser;
    private UserResponseDTO userResponseDTO;
    private PublicUserProfileDTO publicUserDTO;

    @BeforeEach
    void setUp() {
        postUserDTO = new PostUserDTO(
                "  JohnDoe  ",
                "  JOHN.DOE@EMAIL.COM  ",
                "Password123",
                "Some description",
                "https://picture.com/pic.png",
                true
        );

        mappedUser = User.builder()
                .username(postUserDTO.username())
                .email(postUserDTO.email())
                .password(null)
                .description(postUserDTO.description())
                .profilePicture(postUserDTO.profilePicture())
                .isProfilePublic(postUserDTO.isProfilePublic())
                .build();

        savedUser = User.builder()
                .id(UUID.randomUUID())
                .username("JohnDoe")
                .email("john.doe@email.com")
                .password("Password123")
                .description(postUserDTO.description())
                .profilePicture(postUserDTO.profilePicture())
                .isProfilePublic(true)
                .isEmailVerified(true)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
        lenient().when(userRepository.findByIdForUpdate(savedUser.getId())).thenReturn(Optional.of(savedUser));

        userResponseDTO = new UserResponseDTO(
                savedUser.getId(),
                savedUser.getUsername(),
                savedUser.getEmail(),
                savedUser.getDescription(),
                savedUser.getProfilePicture(),
                savedUser.getIsProfilePublic(),
                savedUser.getCreatedAt(),
                savedUser.getUpdatedAt()
        );

        publicUserDTO = new PublicUserProfileDTO(
                savedUser.getId(),
                savedUser.getUsername(),
                savedUser.getDescription(),
                savedUser.getProfilePicture(),
                savedUser.getIsProfilePublic(),
                savedUser.getCreatedAt(),
                0L,
                0L,
                0L,
                0L,
                0L,
                List.of(),
                List.of(),
                null,
                0L,
                0L
        );

        lenient().when(followerRepository.countByFollowedIdAndStatus(any(), any())).thenReturn(0L);
        lenient().when(followerRepository.countByFollowerIdAndStatus(any(), any())).thenReturn(0L);
        lenient().when(userBlockAccessService.relationship(any(), any())).thenReturn(UserBlockRelationship.NONE);
    }

    @Test
    void shouldRemoveBannerAndPreserveProfilePicture() {
        UUID userId = savedUser.getId();
        savedUser.setBanner("https://picture.com/banner.png");
        savedUser.setProfilePicture("https://picture.com/avatar.png");
        UserResponseDTO response = new UserResponseDTO(userId, savedUser.getUsername(), savedUser.getName(), savedUser.getEmail(),
                savedUser.getDescription(), savedUser.getProfilePicture(), savedUser.getIsProfilePublic(), savedUser.getCreatedAt(),
                LocalDateTime.now(), 0, 0, 0, 0, 0, List.of(), List.of(), null, 0, 0, null, null);
        when(userRepository.findById(userId)).thenReturn(Optional.of(savedUser));
        when(userRepository.save(savedUser)).thenReturn(savedUser);
        when(userMapper.userToUserResponseDto(eq(savedUser), anyLong(), anyLong(), anyLong(), anyLong(), anyLong(), any(), any(), anyLong(), anyLong()))
                .thenReturn(response);

        UserResponseDTO result = userService.removeBanner(userId);

        assertThat(result.banner()).isNull();
        assertThat(result.profilePicture()).isEqualTo("https://picture.com/avatar.png");
        assertThat(savedUser.getBanner()).isNull();
        assertThat(savedUser.getProfilePicture()).isEqualTo("https://picture.com/avatar.png");
        verify(userRepository).save(savedUser);
    }

    @Test
    void shouldRemoveBannerIdempotentlyWhenBannerIsAlreadyNull() {
        UUID userId = savedUser.getId();
        LocalDateTime originalUpdatedAt = LocalDateTime.of(2026, 10, 15, 10, 30);
        savedUser.setBanner(null);
        savedUser.setUpdatedAt(originalUpdatedAt);
        when(userRepository.findById(userId)).thenReturn(Optional.of(savedUser));
        when(userMapper.userToUserResponseDto(eq(savedUser), anyLong(), anyLong(), anyLong(), anyLong(), anyLong(), any(), any(), anyLong(), anyLong()))
                .thenReturn(userResponseDTO);

        UserResponseDTO result = userService.removeBanner(userId);

        assertThat(result.banner()).isNull();
        assertThat(savedUser.getUpdatedAt()).isEqualTo(originalUpdatedAt);
        verify(userRepository, never()).save(any());
    }

    @Test
    void shouldThrowNotFoundWhenRemovingBannerForMissingUser() {
        UUID userId = UUID.randomUUID();
        when(userRepository.findById(userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userService.removeBanner(userId))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("User not found");
        verify(userRepository, never()).save(any());
    }

    @Test
    @DisplayName("[startRegistration] Should Return Browser Proof - When Registration Is Valid")
    void shouldReturnBrowserProofWhenRegistrationIsValid() {
        when(userMapper.postUserDtoToUser(postUserDTO)).thenReturn(mappedUser);
        when(passwordEncoder.encode(postUserDTO.password())).thenReturn("hashedPassword");
        when(userRepository.findByEmailOrPendingEmailIgnoreCaseForUpdate("john.doe@email.com"))
                .thenReturn(Optional.empty());
        when(userRepository.findByUsernameIgnoreCase("JohnDoe")).thenReturn(Optional.empty());
        when(emailConfirmationService.issueForRegistration(any(User.class))).thenReturn("browser-proof");

        String result = userService.startRegistration(postUserDTO);

        assertThat(result).isEqualTo("browser-proof");
        verify(emailConfirmationService).issueForRegistration(any(User.class));
        verify(userRepository, never()).saveAndFlush(any(User.class));
    }

    @Test
    @DisplayName("[startRegistration] Should Normalize Email, Username And Password - When Data Is Valid")
    void shouldNormalizeEmailUsernameAndPasswordBeforeCreatingAttemptWhenDataIsValid() {
        when(userMapper.postUserDtoToUser(postUserDTO)).thenReturn(mappedUser);
        when(passwordEncoder.encode(postUserDTO.password())).thenReturn("hashedPassword");
        when(userRepository.findByEmailOrPendingEmailIgnoreCaseForUpdate("john.doe@email.com"))
                .thenReturn(Optional.empty());
        when(userRepository.findByUsernameIgnoreCase("JohnDoe")).thenReturn(Optional.empty());
        when(emailConfirmationService.issueForRegistration(any(User.class))).thenReturn("browser-proof");

        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);

        userService.startRegistration(postUserDTO);

        verify(emailConfirmationService).issueForRegistration(userCaptor.capture());
        User capturedUser = userCaptor.getValue();

        assertThat(capturedUser.getUsername()).isEqualTo("JohnDoe");
        assertThat(capturedUser.getEmail()).isEqualTo("john.doe@email.com");
        assertThat(capturedUser.getPassword()).isEqualTo("hashedPassword");
    }

    @Test
    @DisplayName("[startRegistration] Should Keep The Draft Unverified - Until The Email Is Confirmed")
    void shouldKeepTheDraftUnverifiedUntilTheEmailIsConfirmed() {
        when(userMapper.postUserDtoToUser(postUserDTO)).thenReturn(mappedUser);
        when(passwordEncoder.encode(postUserDTO.password())).thenReturn("hashedPassword");
        when(userRepository.findByEmailOrPendingEmailIgnoreCaseForUpdate("john.doe@email.com"))
                .thenReturn(Optional.empty());
        when(userRepository.findByUsernameIgnoreCase("JohnDoe")).thenReturn(Optional.empty());
        when(emailConfirmationService.issueForRegistration(any(User.class))).thenReturn("browser-proof");

        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);

        userService.startRegistration(postUserDTO);

        verify(emailConfirmationService).issueForRegistration(userCaptor.capture());
        assertThat(userCaptor.getValue().getIsEmailVerified()).isFalse();
    }

    @Test
    void shouldNotReplaceAnExistingUnverifiedUserWhenRegistrationUsesItsEmail() {
        User existing = User.builder().username("preclaimed").email("john.doe@email.com")
                .password("existing-password-hash").isEmailVerified(false).build();
        when(userRepository.findByEmailOrPendingEmailIgnoreCaseForUpdate("john.doe@email.com"))
                .thenReturn(Optional.of(existing));
        when(userMapper.postUserDtoToUser(postUserDTO)).thenReturn(mappedUser);
        when(passwordEncoder.encode(postUserDTO.password())).thenReturn("new-password-hash");

        assertThatThrownBy(() -> userService.startRegistration(postUserDTO))
                .isInstanceOf(ConflictException.class)
                .hasMessage("Email already in use");

        assertThat(existing.getUsername()).isEqualTo("preclaimed");
        assertThat(existing.getPassword()).isEqualTo("existing-password-hash");
        verify(userRepository, never()).saveAndFlush(any(User.class));
        verify(emailConfirmationService, never()).issueForRegistration(any(User.class));
    }

    @Test
    void shouldRejectRegistrationWhenSubmittedEmailIsPendingOnAnotherAccount() {
        User accountWithPendingEmail = User.builder().username("existinguser").email("current@email.com")
                .pendingEmail("john.doe@email.com").password("existing-password-hash").isEmailVerified(true).build();
        when(userMapper.postUserDtoToUser(postUserDTO)).thenReturn(mappedUser);
        when(passwordEncoder.encode(postUserDTO.password())).thenReturn("new-password-hash");
        when(userRepository.findByEmailOrPendingEmailIgnoreCaseForUpdate("john.doe@email.com"))
                .thenReturn(Optional.of(accountWithPendingEmail));

        assertThatThrownBy(() -> userService.startRegistration(postUserDTO))
                .isInstanceOf(ConflictException.class)
                .hasMessage("Email already in use");

        assertThat(accountWithPendingEmail.getEmail()).isEqualTo("current@email.com");
        assertThat(accountWithPendingEmail.getPendingEmail()).isEqualTo("john.doe@email.com");
        verify(userRepository, never()).saveAndFlush(any(User.class));
        verify(emailConfirmationService, never()).issueForRegistration(any(User.class));
    }

    @Test
    void shouldRejectRegistrationWhenUsernameIsAlreadyInUseIgnoringCase() {
        User existing = User.builder().username("johndoe").email("other@email.com")
                .password("existing-password-hash").build();
        when(userMapper.postUserDtoToUser(postUserDTO)).thenReturn(mappedUser);
        when(passwordEncoder.encode(postUserDTO.password())).thenReturn("new-password-hash");
        when(userRepository.findByEmailOrPendingEmailIgnoreCaseForUpdate("john.doe@email.com"))
                .thenReturn(Optional.empty());
        when(userRepository.findByUsernameIgnoreCase("JohnDoe")).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> userService.startRegistration(postUserDTO))
                .isInstanceOf(ConflictException.class)
                .hasMessage("Username already in use");

        verify(emailConfirmationService, never()).issueForRegistration(any(User.class));
    }
    @Test
    @DisplayName("[startRegistration] Should Throw BadRequestException - When Trimmed Username Is Shorter Than The Minimum Length")
    void shouldThrowBadRequestExceptionWhenTrimmedUsernameIsShorterThanTheMinimumLength() {
        PostUserDTO postUserDTOWithPaddedShortUsername = new PostUserDTO(
                " ab ",
                postUserDTO.email(),
                postUserDTO.password(),
                postUserDTO.description(),
                postUserDTO.profilePicture(),
                postUserDTO.isProfilePublic()
        );

        assertThatThrownBy(() -> userService.startRegistration(postUserDTOWithPaddedShortUsername))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("Username must be at least 3 characters long");

        verify(userRepository, never()).save(any());
    }

    @Test
    @DisplayName("[getUserById] Should Return PublicUserProfileDTO - When Id Exists And Profile Is Public")
    void shouldReturnPublicUserDtoWhenIdExistsAndProfileIsPublic() {
        UUID id = savedUser.getId();
        when(userRepository.findById(id)).thenReturn(Optional.of(savedUser));
        when(userMapper.userToPublicUserProfileDto(savedUser, 0L, 0L, 0L, 0L, 0L, List.of(), List.of(), 0L, 0L)).thenReturn(publicUserDTO);

        PublicUserProfileDTO result = userService.getUserById(UUID.randomUUID(), id);

        assertThat(result).isEqualTo(publicUserDTO);
        verify(userRepository).findById(id);
        verify(userMapper).userToPublicUserProfileDto(savedUser, 0L, 0L, 0L, 0L, 0L, List.of(), List.of(), 0L, 0L);
    }

    @Test
    @DisplayName("[getUserById] Should Throw NotFoundException - When Id Does Not Exist")
    void shouldThrowNotFoundExceptionWhenIdDoesNotExist() {
        UUID id = UUID.randomUUID();
        when(userRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userService.getUserById(UUID.randomUUID(), id))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("User not found");

        verify(userMapper, never()).userToPublicUserProfileDto(any(), anyLong(), anyLong(), anyLong(), anyLong(), anyLong(), any(), any(), anyLong(), anyLong());
    }

    @Test
    @DisplayName("[getUserById] Should Return Minimum Profile With Bio - When Private And Not Followed")
    void shouldReturnMinimumProfileWithBioWhenPrivateAndNotFollowed() {
        UUID viewerId = UUID.randomUUID();
        UUID id = savedUser.getId();
        savedUser.setIsProfilePublic(false);
        savedUser.setDescription("Visible biography");
        when(userRepository.findById(id)).thenReturn(Optional.of(savedUser));
        doThrow(new ForbiddenException("This user profile is private"))
                .when(userVisibilityService).assertCanView(viewerId, id);

        PublicUserProfileDTO result = userService.getUserById(viewerId, id);

        assertThat(result.id()).isEqualTo(id);
        assertThat(result.username()).isEqualTo(savedUser.getUsername());
        assertThat(result.name()).isEqualTo(savedUser.getName());
        assertThat(result.description()).isEqualTo("Visible biography");
        assertThat(result.profilePicture()).isEqualTo(savedUser.getProfilePicture());
        assertThat(result.totalMinutesWatchedMovies()).isNull();
        assertThat(result.followersCount()).isNull();
        assertThat(result.viewerRelation()).isEqualTo(ViewerProfileRelation.NOT_FOLLOWING);
        verify(userMapper, never()).userToPublicUserProfileDto(any(), anyLong(), anyLong(), anyLong(), anyLong(), anyLong(), any(), any(), anyLong(), anyLong());
    }

    @Test
    @DisplayName("[getUserById] Should Return Private Profile - When Requester Is The Profile Owner")
    void shouldReturnPrivateProfileWhenRequesterIsTheProfileOwner() {
        UUID id = savedUser.getId();
        when(userRepository.findById(id)).thenReturn(Optional.of(savedUser));
        doNothing().when(userVisibilityService).assertCanView(id, id);
        when(userMapper.userToPublicUserProfileDto(savedUser, 0L, 0L, 0L, 0L, 0L, List.of(), List.of(), 0L, 0L))
                .thenReturn(publicUserDTO);

        PublicUserProfileDTO result = userService.getUserById(id, id);

        assertThat(result.viewerRelation()).isEqualTo(ViewerProfileRelation.SELF);
    }

    @Test
    @DisplayName("[getUserById] Should Return Private Profile - When Requester Is An Accepted Follower")
    void shouldReturnPrivateProfileWhenRequesterIsAnAcceptedFollower() {
        UUID viewerId = UUID.randomUUID();
        UUID id = savedUser.getId();
        when(userRepository.findById(id)).thenReturn(Optional.of(savedUser));
        doNothing().when(userVisibilityService).assertCanView(viewerId, id);
        when(userMapper.userToPublicUserProfileDto(savedUser, 0L, 0L, 0L, 0L, 0L, List.of(), List.of(), 0L, 0L))
                .thenReturn(publicUserDTO);

        PublicUserProfileDTO result = userService.getUserById(viewerId, id);

        assertThat(result).isEqualTo(publicUserDTO);
        verify(userVisibilityService).assertCanView(viewerId, id);
    }

    @Test
    @DisplayName("[getUserById] Should Return Following Relation - When Viewer Follows Target")
    void shouldReturnFollowingRelationWhenViewerFollowsTarget() {
        UUID viewerId = UUID.randomUUID();
        UUID targetId = savedUser.getId();
        Follower relation = mock(Follower.class);

        when(userRepository.findById(targetId)).thenReturn(Optional.of(savedUser));
        doNothing().when(userVisibilityService).assertCanView(viewerId, targetId);
        when(followerRepository.findByFollowerIdAndFollowedId(viewerId, targetId))
                .thenReturn(Optional.of(relation));
        when(relation.getStatus()).thenReturn(FollowStatus.ACCEPTED);
        when(userMapper.userToPublicUserProfileDto(savedUser, 0L, 0L, 0L, 0L, 0L, List.of(), List.of(), 0L, 0L))
                .thenReturn(publicUserDTO);

        PublicUserProfileDTO result = userService.getUserById(viewerId, targetId);

        assertThat(result.viewerRelation()).isEqualTo(ViewerProfileRelation.FOLLOWING);
    }

    @Test
    void shouldReturnOnlySafeIdentityWhenEitherUserBlockedTheOther() {
        UUID viewerId = UUID.randomUUID();
        UUID targetId = savedUser.getId();
        savedUser.setName("Target Name");
        when(userRepository.findById(targetId)).thenReturn(Optional.of(savedUser));
        when(userBlockAccessService.relationship(viewerId, targetId)).thenReturn(UserBlockRelationship.BLOCKED_ME);

        PublicUserProfileDTO result = userService.getUserById(viewerId, targetId);

        assertThat(result.id()).isEqualTo(targetId);
        assertThat(result.username()).isEqualTo(savedUser.getUsername());
        assertThat(result.name()).isEqualTo("Target Name");
        assertThat(result.description()).isNull();
        assertThat(result.profilePicture()).isNull();
        assertThat(result.banner()).isNull();
        assertThat(result.followersCount()).isNull();
        assertThat(result.totalMinutesWatchedMovies()).isNull();
        assertThat(result.genreCountsMovies()).isNull();
        assertThat(result.blockRelationship()).isEqualTo(UserBlockRelationship.BLOCKED_ME);
        assertThat(result.isContentRestrictedByBlock()).isTrue();
        verify(userVisibilityService, never()).assertCanView(any(), any());
        verify(userMapper, never()).userToPublicUserProfileDto(any(), anyLong(), anyLong(), anyLong(), anyLong(), anyLong(), any(), any(), anyLong(), anyLong());
    }

    @Test
    @DisplayName("[getCurrentUser] Should Return UserResponseDTO - When Id Exists")
    void shouldReturnUserResponseDtoWhenIdExists() {
        UUID id = savedUser.getId();
        when(userRepository.findById(id)).thenReturn(Optional.of(savedUser));
        when(userMapper.userToUserResponseDto(savedUser, 0L, 0L, 0L, 0L, 0L, List.of(), List.of(), 0L, 0L)).thenReturn(userResponseDTO);

        UserResponseDTO result = userService.getCurrentUser(id);

        assertThat(result).isEqualTo(userResponseDTO);
        verify(userRepository).findById(id);
        verify(userMapper).userToUserResponseDto(savedUser, 0L, 0L, 0L, 0L, 0L, List.of(), List.of(), 0L, 0L);
    }

    @Test
    @DisplayName("[getCurrentUser] Should Return UserResponseDTO - When Profile Is Private")
    void shouldReturnUserResponseDtoWhenProfileIsPrivate() {
        UUID id = savedUser.getId();
        savedUser.setIsProfilePublic(false);
        when(userRepository.findById(id)).thenReturn(Optional.of(savedUser));
        when(userMapper.userToUserResponseDto(savedUser, 0L, 0L, 0L, 0L, 0L, List.of(), List.of(), 0L, 0L)).thenReturn(userResponseDTO);

        UserResponseDTO result = userService.getCurrentUser(id);

        assertThat(result).isEqualTo(userResponseDTO);
    }

    @Test
    @DisplayName("[getCurrentUser] Should Propagate Computed Watch Stats To The Mapper - When Diary Has Entries")
    void shouldPropagateComputedWatchStatsToTheMapperWhenDiaryHasEntries() {
        UUID id = savedUser.getId();
        DiaryEntryRepository.GenreCount actionGenre = mock(DiaryEntryRepository.GenreCount.class);
        when(actionGenre.getGenre()).thenReturn("Action");
        when(actionGenre.getCount()).thenReturn(5L);

        when(userRepository.findById(id)).thenReturn(Optional.of(savedUser));
        when(diaryEntryRepository.sumRuntimeMinutesByUserIdAndContentType(id, ContentType.MOVIE)).thenReturn(4200L);
        when(diaryEntryRepository.sumRuntimeMinutesByUserIdAndContentType(id, ContentType.EPISODE)).thenReturn(1800L);
        when(diaryEntryRepository.sumRuntimeMinutesByUserIdAndContentTypeAndWatchedDateBetween(eq(id), eq(ContentType.MOVIE), any(), any())).thenReturn(300L);
        when(diaryEntryRepository.sumRuntimeMinutesByUserIdAndContentTypeAndWatchedDateBetween(eq(id), eq(ContentType.EPISODE), any(), any())).thenReturn(150L);
        when(diaryEntryRepository.countEntriesByGenreAndUserIdForMovies(id)).thenReturn(List.of(actionGenre));
        when(userMapper.userToUserResponseDto(eq(savedUser), anyLong(), anyLong(), anyLong(), anyLong(), anyLong(), any(), any(), anyLong(), anyLong())).thenReturn(userResponseDTO);

        ArgumentCaptor<Long> totalCaptor = ArgumentCaptor.forClass(Long.class);
        ArgumentCaptor<Long> totalEpisodesCaptor = ArgumentCaptor.forClass(Long.class);
        ArgumentCaptor<Long> last30MoviesCaptor = ArgumentCaptor.forClass(Long.class);
        ArgumentCaptor<Long> last30EpisodesCaptor = ArgumentCaptor.forClass(Long.class);
        ArgumentCaptor<List<GenreCountDTO>> genreMoviesCaptor = ArgumentCaptor.forClass(List.class);

        userService.getCurrentUser(id);

        verify(userMapper).userToUserResponseDto(eq(savedUser), totalCaptor.capture(), totalEpisodesCaptor.capture(),
                last30MoviesCaptor.capture(), last30EpisodesCaptor.capture(), anyLong(), genreMoviesCaptor.capture(),
                any(), anyLong(), anyLong());
        assertThat(totalCaptor.getValue()).isEqualTo(4200L);
        assertThat(totalEpisodesCaptor.getValue()).isEqualTo(1800L);
        assertThat(last30MoviesCaptor.getValue()).isEqualTo(300L);
        assertThat(last30EpisodesCaptor.getValue()).isEqualTo(150L);
        assertThat(genreMoviesCaptor.getValue()).containsExactly(new GenreCountDTO("Action", 5L));
    }

    @Test
    @DisplayName("[getCurrentUser] Should Propagate Computed Followers And Following Counts To The Mapper - When User Has Followers")
    void shouldPropagateComputedFollowersAndFollowingCountsToTheMapperWhenUserHasFollowers() {
        UUID id = savedUser.getId();
        when(userRepository.findById(id)).thenReturn(Optional.of(savedUser));
        when(followerRepository.countByFollowedIdAndStatus(id, FollowStatus.ACCEPTED)).thenReturn(42L);
        when(followerRepository.countByFollowerIdAndStatus(id, FollowStatus.ACCEPTED)).thenReturn(17L);
        when(userMapper.userToUserResponseDto(eq(savedUser), anyLong(), anyLong(), anyLong(), anyLong(), anyLong(), any(), any(), anyLong(), anyLong())).thenReturn(userResponseDTO);

        ArgumentCaptor<Long> followersCaptor = ArgumentCaptor.forClass(Long.class);
        ArgumentCaptor<Long> followingCaptor = ArgumentCaptor.forClass(Long.class);

        userService.getCurrentUser(id);

        verify(userMapper).userToUserResponseDto(eq(savedUser), anyLong(), anyLong(), anyLong(), anyLong(), anyLong(), any(), any(), followersCaptor.capture(), followingCaptor.capture());
        assertThat(followersCaptor.getValue()).isEqualTo(42L);
        assertThat(followingCaptor.getValue()).isEqualTo(17L);
    }

    @Test
    @DisplayName("[startRegistration] Should Not Query Watch Stats - When Creating A Pending Registration")
    void shouldNotQueryWatchStatsWhenRegisteringANewUser() {
        when(userMapper.postUserDtoToUser(postUserDTO)).thenReturn(mappedUser);
        when(passwordEncoder.encode(postUserDTO.password())).thenReturn("hashedPassword");

        userService.startRegistration(postUserDTO);

        verifyNoInteractions(diaryEntryRepository);
    }

    @Test
    @DisplayName("[getCurrentUser] Should Throw NotFoundException - When Id Does Not Exist")
    void shouldThrowNotFoundExceptionWhenGettingCurrentUserThatDoesNotExist() {
        UUID id = UUID.randomUUID();
        when(userRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userService.getCurrentUser(id))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("User not found");

        verify(userMapper, never()).userToUserResponseDto(any(), anyLong(), anyLong(), anyLong(), anyLong(), anyLong(), any(), any(), anyLong(), anyLong());
    }

    @Test
    @DisplayName("[getUsersByUsername] Should Throw BadRequestException - When Username Is Null")
    void shouldThrowBadRequestExceptionWhenUsernameIsNull() {
        assertThrows(BadRequestException.class,
                () -> userService.getUsersByUsername(null, 1, 10));

        verifyNoInteractions(userRepository, userMapper);
    }

    @Test
    @DisplayName("[getUsersByUsername] Should Throw BadRequestException - When Username Is Empty")
    void shouldThrowBadRequestExceptionWhenUsernameIsEmpty() {
        assertThrows(BadRequestException.class,
                () -> userService.getUsersByUsername("", 1, 10));

        verifyNoInteractions(userRepository, userMapper);
    }

    @Test
    @DisplayName("[getUsersByUsername] Should Throw BadRequestException - When Username Is Only Whitespace")
    void shouldThrowBadRequestExceptionWhenUsernameIsOnlyWhitespace() {
        assertThrows(BadRequestException.class,
                () -> userService.getUsersByUsername("   ", 1, 10));

        verifyNoInteractions(userRepository, userMapper);
    }

    @Test
    @DisplayName("[getUsersByUsername] Should Trim Username Before Query - When Username Has Surrounding Whitespace")
    void shouldTrimUsernameBeforeQueryWhenUsernameHasSurroundingWhitespace() {
        when(userRepository.findByUsernameStartingWithIgnoreCase(eq("john"), anyString(), any(PageRequest.class)))
                .thenReturn(Page.empty());

        userService.getUsersByUsername("  john  ", 1, 10);

        verify(userRepository).findByUsernameStartingWithIgnoreCase(eq("john"), anyString(), any(PageRequest.class));
    }

    @Test
    @DisplayName("[getUsersByUsername] Should Escape LIKE Wildcards - When Username Contains Percent Or Underscore")
    void shouldEscapeLikeWildcardsWhenUsernameContainsPercentOrUnderscore() {
        when(userRepository.findByUsernameStartingWithIgnoreCase(eq("50%_off"), anyString(), any(PageRequest.class)))
                .thenReturn(Page.empty());

        userService.getUsersByUsername("50%_off", 1, 10);

        verify(userRepository).findByUsernameStartingWithIgnoreCase(
                eq("50%_off"), eq("50\\%\\_off"), any(PageRequest.class));
    }

    @Test
    @DisplayName("[getUsersByUsername] Should Return Page Of DTOs - When Happy Path")
    void shouldReturnPageOfDtosWhenHappyPath() {
        String username = "john";
        Page<User> userPage = new PageImpl<>(List.of(savedUser));
        UserPreviewDTO dto = new UserPreviewDTO(
                savedUser.getId(),
                savedUser.getUsername(),
                savedUser.getProfilePicture(),
                savedUser.getIsProfilePublic()
        );

        when(userRepository.findByUsernameStartingWithIgnoreCase(eq(username), anyString(), any(PageRequest.class)))
                .thenReturn(userPage);
        when(userMapper.userToUserPreviewDto(savedUser)).thenReturn(dto);

        Page<UserPreviewDTO> result = userService.getUsersByUsername(username, 1, 10);

        assertEquals(1, result.getTotalElements());
        assertEquals(dto, result.getContent().get(0));
        verify(userMapper).userToUserPreviewDto(savedUser);
    }

    @Test
    @DisplayName("[getUsersByUsername] Should Return Empty Page - When No Users Are Found")
    void shouldReturnEmptyPageWhenNoUsersFound() {
        String username = "john";

        when(userRepository.findByUsernameStartingWithIgnoreCase(eq(username), anyString(), any(PageRequest.class)))
                .thenReturn(Page.empty());

        Page<UserPreviewDTO> result = userService.getUsersByUsername(username, 1, 10);

        assertTrue(result.isEmpty());
        verify(userMapper, never()).userToUserPreviewDto(any());
    }

    private void stubRepositoryReturningEmptyPage() {
        when(userRepository.findByUsernameStartingWithIgnoreCase(anyString(), anyString(), any(PageRequest.class)))
                .thenReturn(Page.empty());
    }

    @Test
    @DisplayName("[getUsersByUsername] Should Use Default Page - When Page Number Is Null")
    void shouldUseDefaultPageWhenPageNumberIsNull() {
        stubRepositoryReturningEmptyPage();

        userService.getUsersByUsername("john", null, 10);

        verify(userRepository).findByUsernameStartingWithIgnoreCase(anyString(), anyString(), pageRequestCaptor.capture());
        assertEquals(PageRequestFactory.DEFAULT_PAGE, pageRequestCaptor.getValue().getPageNumber());
    }

    @Test
    @DisplayName("[getUsersByUsername] Should Use Default Page - When Page Number Is Zero")
    void shouldUseDefaultPageWhenPageNumberIsZero() {
        stubRepositoryReturningEmptyPage();

        userService.getUsersByUsername("john", 0, 10);

        verify(userRepository).findByUsernameStartingWithIgnoreCase(anyString(), anyString(), pageRequestCaptor.capture());
        assertEquals(PageRequestFactory.DEFAULT_PAGE, pageRequestCaptor.getValue().getPageNumber());
    }

    @Test
    @DisplayName("[getUsersByUsername] Should Use Page Number Minus One - When Page Number Is Positive")
    void shouldUsePageNumberMinusOneWhenPageNumberIsPositive() {
        stubRepositoryReturningEmptyPage();

        userService.getUsersByUsername("john", 3, 10);

        verify(userRepository).findByUsernameStartingWithIgnoreCase(anyString(), anyString(), pageRequestCaptor.capture());
        assertEquals(2, pageRequestCaptor.getValue().getPageNumber());
    }

    @Test
    @DisplayName("[getUsersByUsername] Should Throw BadRequestException - When Page Number Is Negative")
    void shouldThrowBadRequestExceptionWhenPageNumberIsNegative() {
        assertThrows(BadRequestException.class,
                () -> userService.getUsersByUsername("john", -1, 10));

        verifyNoInteractions(userRepository, userMapper);
    }

    @Test
    @DisplayName("[getUsersByUsername] Should Use Default Page Size - When Page Size Is Null")
    void shouldUseDefaultPageSizeWhenPageSizeIsNull() {
        stubRepositoryReturningEmptyPage();

        userService.getUsersByUsername("john", 1, null);

        verify(userRepository).findByUsernameStartingWithIgnoreCase(anyString(), anyString(), pageRequestCaptor.capture());
        assertEquals(PageRequestFactory.DEFAULT_PAGE_SIZE, pageRequestCaptor.getValue().getPageSize());
    }

    @Test
    @DisplayName("[getUsersByUsername] Should Clamp Page Size To Max Limit - When Page Size Exceeds Limit")
    void shouldClampPageSizeToMaxLimitWhenPageSizeExceedsLimit() {
        stubRepositoryReturningEmptyPage();

        userService.getUsersByUsername("john", 1, 1001);

        verify(userRepository).findByUsernameStartingWithIgnoreCase(anyString(), anyString(), pageRequestCaptor.capture());
        assertEquals(PageRequestFactory.MAX_PAGE_SIZE, pageRequestCaptor.getValue().getPageSize());
    }

    @Test
    @DisplayName("[getUsersByUsername] Should Use Provided Page Size - When Page Size Is Valid")
    void shouldUseProvidedPageSizeWhenPageSizeIsValid() {
        stubRepositoryReturningEmptyPage();

        userService.getUsersByUsername("john", 1, 25);

        verify(userRepository).findByUsernameStartingWithIgnoreCase(anyString(), anyString(), pageRequestCaptor.capture());
        assertEquals(25, pageRequestCaptor.getValue().getPageSize());
    }

    @Test
    @DisplayName("[getUsersByUsername] Should Use Provided Page Size - When Page Size Is At Max Limit")
    void shouldUseProvidedPageSizeWhenPageSizeIsAtMaxLimit() {
        stubRepositoryReturningEmptyPage();

        userService.getUsersByUsername("john", 1, 1000);

        verify(userRepository).findByUsernameStartingWithIgnoreCase(anyString(), anyString(), pageRequestCaptor.capture());
        assertEquals(1000, pageRequestCaptor.getValue().getPageSize());
    }

    @Test
    @DisplayName("[getUsersByUsername] Should Throw BadRequestException - When Page Size Is Negative")
    void shouldThrowBadRequestExceptionWhenPageSizeIsNegative() {
        assertThrows(BadRequestException.class,
                () -> userService.getUsersByUsername("john", 1, -5));

        verifyNoInteractions(userRepository, userMapper);
    }

    @Test
    @DisplayName("[getUsersByUsername] Should Throw BadRequestException - When Page Size Is Zero")
    void shouldThrowBadRequestExceptionWhenPageSizeIsZero() {
        assertThrows(BadRequestException.class,
                () -> userService.getUsersByUsername("john", 1, 0));

        verifyNoInteractions(userRepository, userMapper);
    }

    @Test
    @DisplayName("[getUsersByUsername] Should Not Apply Sort - When SortBy Is Always Null")
    void shouldNotApplySortWhenSortByIsAlwaysNull() {
        stubRepositoryReturningEmptyPage();

        userService.getUsersByUsername("john", 1, 10);

        verify(userRepository).findByUsernameStartingWithIgnoreCase(anyString(), anyString(), pageRequestCaptor.capture());
        assertTrue(pageRequestCaptor.getValue().getSort().isUnsorted());
    }

    @Test
    @DisplayName("[updateUser] Should Not Change Any Field - When All Patch Fields Are Null")
    void shouldNotChangeAnyFieldWhenAllPatchFieldsAreNull() {
        PatchUserDTO patchUserDTO = new PatchUserDTO(null, null, null, null, null, null, null);
        when(userRepository.saveAndFlush(any(User.class))).thenReturn(savedUser);
        when(userMapper.userToUserResponseDto(savedUser, 0L, 0L, 0L, 0L, 0L, List.of(), List.of(), 0L, 0L)).thenReturn(userResponseDTO);

        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);

        userService.updateUser(savedUser, patchUserDTO);

        verify(userRepository).saveAndFlush(userCaptor.capture());
        User capturedUser = userCaptor.getValue();

        assertThat(capturedUser.getUsername()).isEqualTo(savedUser.getUsername());
        assertThat(capturedUser.getEmail()).isEqualTo(savedUser.getEmail());
        assertThat(capturedUser.getPassword()).isEqualTo(savedUser.getPassword());
        assertThat(capturedUser.getDescription()).isEqualTo(savedUser.getDescription());
        assertThat(capturedUser.getProfilePicture()).isEqualTo(savedUser.getProfilePicture());
        assertThat(capturedUser.getIsProfilePublic()).isEqualTo(savedUser.getIsProfilePublic());
        verifyNoInteractions(passwordEncoder);
    }

    @Test
    @DisplayName("[updateUser] Should Update Username Trimmed - When A Different Value Is Provided")
    void shouldUpdateUsernameTrimmedWhenDifferentValueProvided() {
        PatchUserDTO patchUserDTO = new PatchUserDTO("  NewUsername  ", null, null, null, null, null, null);
        when(userRepository.saveAndFlush(any(User.class))).thenReturn(savedUser);
        when(userMapper.userToUserResponseDto(savedUser, 0L, 0L, 0L, 0L, 0L, List.of(), List.of(), 0L, 0L)).thenReturn(userResponseDTO);

        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);

        userService.updateUser(savedUser, patchUserDTO);

        verify(userRepository).saveAndFlush(userCaptor.capture());
        assertThat(userCaptor.getValue().getUsername()).isEqualTo("NewUsername");
    }

    @Test
    @DisplayName("[updateUser] Should Not Change Username - When The Same Value Is Provided")
    void shouldNotChangeUsernameWhenSameValueProvided() {
        PatchUserDTO patchUserDTO = new PatchUserDTO("  " + savedUser.getUsername() + "  ", null, null, null, null, null, null);
        when(userRepository.saveAndFlush(any(User.class))).thenReturn(savedUser);
        when(userMapper.userToUserResponseDto(savedUser, 0L, 0L, 0L, 0L, 0L, List.of(), List.of(), 0L, 0L)).thenReturn(userResponseDTO);

        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);

        userService.updateUser(savedUser, patchUserDTO);

        verify(userRepository).saveAndFlush(userCaptor.capture());
        assertThat(userCaptor.getValue().getUsername()).isEqualTo(savedUser.getUsername());
    }

    @Test
    @DisplayName("[updateUser] Should Throw BadRequestException - When Trimmed Username Is Shorter Than The Minimum Length")
    void shouldThrowBadRequestExceptionWhenTrimmedUsernameIsShorterThanTheMinimumLengthOnUpdate() {
        PatchUserDTO patchUserDTO = new PatchUserDTO(" ab ", null, null, null, null, null, null);

        assertThatThrownBy(() -> userService.updateUser(savedUser, patchUserDTO))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("Username must be at least 3 characters long");

        verify(userRepository, never()).save(any());
    }

    @Test
    @DisplayName("[updateUser] Should Keep Current Email And Store Normalized Pending Email - When A Different Value Is Provided")
    void shouldKeepCurrentEmailAndStoreNormalizedPendingEmailWhenDifferentValueProvided() {
        PatchUserDTO patchUserDTO = new PatchUserDTO(null, "  NEW.EMAIL@EMAIL.COM  ", null, null, null, null, "Password123");
        when(passwordEncoder.matches("Password123", savedUser.getPassword())).thenReturn(true);
        when(userRepository.saveAndFlush(any(User.class))).thenReturn(savedUser);
        when(userMapper.userToUserResponseDto(savedUser, 0L, 0L, 0L, 0L, 0L, List.of(), List.of(), 0L, 0L)).thenReturn(userResponseDTO);

        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);

        userService.updateUser(savedUser, patchUserDTO);

        verify(userRepository).saveAndFlush(userCaptor.capture());
        assertThat(userCaptor.getValue().getEmail()).isEqualTo("john.doe@email.com");
        assertThat(userCaptor.getValue().getPendingEmail()).isEqualTo("new.email@email.com");
    }

    @Test
    @DisplayName("[updateUser] Should Reject Email Used As Another Account Current Or Pending Email")
    void shouldRejectEmailUsedAsAnotherAccountCurrentOrPendingEmail() {
        PatchUserDTO patchUserDTO = new PatchUserDTO(null, "new.email@email.com", null, null, null, null,
                "Password123");
        when(passwordEncoder.matches("Password123", savedUser.getPassword())).thenReturn(true);
        when(userRepository.existsEmailOrPendingEmailByAnotherUser(savedUser.getId(), "new.email@email.com"))
                .thenReturn(true);

        assertThatThrownBy(() -> userService.updateUser(savedUser, patchUserDTO))
                .isInstanceOf(ConflictException.class)
                .hasMessage("Email already in use");

        assertThat(savedUser.getEmail()).isEqualTo("john.doe@email.com");
        assertThat(savedUser.getPendingEmail()).isNull();
        verify(userRepository, never()).saveAndFlush(any());
        verify(emailConfirmationService, never()).issueAndSend(any(User.class));
    }

    @Test
    @DisplayName("[updateUser] Should Require Confirmation For A New Email - When Current Email Was Verified")
    void shouldRequireConfirmationForNewEmailWhenCurrentEmailWasVerified() {
        savedUser.setIsEmailVerified(true);
        PatchUserDTO patchUserDTO = new PatchUserDTO(null, "new.email@email.com", null, null, null, null,
                "Password123");
        when(passwordEncoder.matches("Password123", savedUser.getPassword())).thenReturn(true);
        when(userRepository.saveAndFlush(any(User.class))).thenReturn(savedUser);
        when(userMapper.userToUserResponseDto(savedUser, 0L, 0L, 0L, 0L, 0L, List.of(), List.of(), 0L, 0L))
                .thenReturn(userResponseDTO);

        userService.updateUser(savedUser, patchUserDTO);

        assertThat(savedUser.getEmail()).isEqualTo("john.doe@email.com");
        assertThat(savedUser.getPendingEmail()).isEqualTo("new.email@email.com");
        assertThat(savedUser.getIsEmailVerified()).isTrue();
        verify(emailConfirmationService).requireDeliveryConfigured();
        verify(emailConfirmationService).issueAndSend(savedUser);
    }

    @Test
    @DisplayName("[updateUser] Should Keep Current Email - When Confirmation Delivery Is Not Configured")
    void shouldKeepCurrentEmailWhenConfirmationDeliveryIsNotConfigured() {
        savedUser.setIsEmailVerified(true);
        PatchUserDTO patchUserDTO = new PatchUserDTO(null, "new.email@email.com", null, null, null, null,
                "Password123");
        when(passwordEncoder.matches("Password123", savedUser.getPassword())).thenReturn(true);
        doThrow(new ServiceUnavailableException("Email confirmation is not configured"))
                .when(emailConfirmationService).requireDeliveryConfigured();

        assertThatThrownBy(() -> userService.updateUser(savedUser, patchUserDTO))
                .isInstanceOf(ServiceUnavailableException.class)
                .hasMessage("Email confirmation is not configured");

        assertThat(savedUser.getEmail()).isEqualTo("john.doe@email.com");
        assertThat(savedUser.getIsEmailVerified()).isTrue();
        assertThat(savedUser.getPendingEmail()).isNull();
        verify(userRepository, never()).saveAndFlush(any());
        verify(emailConfirmationService, never()).issueAndSend(any(User.class));
    }

    @Test
    void shouldRejectWhitespaceEmailWithoutChangingCurrentOrPendingEmail() {
        PatchUserDTO patchUserDTO = new PatchUserDTO(null, "   ", null, null, null, null, "Password123");

        assertThatThrownBy(() -> userService.updateUser(savedUser, patchUserDTO))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("Email must not be blank");

        assertThat(savedUser.getEmail()).isEqualTo("john.doe@email.com");
        assertThat(savedUser.getPendingEmail()).isNull();
        verify(userRepository, never()).saveAndFlush(any());
        verifyNoInteractions(emailConfirmationService);
    }

    @Test
    @DisplayName("[updateUser] Should Not Change Email - When The Same Value Is Provided")
    void shouldNotChangeEmailWhenSameValueProvided() {
        PatchUserDTO patchUserDTO = new PatchUserDTO(null, "  " + savedUser.getEmail().toUpperCase() + "  ", null, null, null, null, null);
        when(userRepository.saveAndFlush(any(User.class))).thenReturn(savedUser);
        when(userMapper.userToUserResponseDto(savedUser, 0L, 0L, 0L, 0L, 0L, List.of(), List.of(), 0L, 0L)).thenReturn(userResponseDTO);

        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);

        userService.updateUser(savedUser, patchUserDTO);

        verify(userRepository).saveAndFlush(userCaptor.capture());
        assertThat(userCaptor.getValue().getEmail()).isEqualTo(savedUser.getEmail());
    }

    @Test
    @DisplayName("[updateUser] Should Not Re-Encode Password - When Provided Password Matches Current Hash")
    void shouldNotReEncodePasswordWhenProvidedPasswordMatchesCurrentHash() {
        PatchUserDTO patchUserDTO = new PatchUserDTO(null, null, "SamePassword123", null, null, null, null);
        when(passwordEncoder.matches("SamePassword123", savedUser.getPassword())).thenReturn(true);
        when(userRepository.saveAndFlush(any(User.class))).thenReturn(savedUser);
        when(userMapper.userToUserResponseDto(savedUser, 0L, 0L, 0L, 0L, 0L, List.of(), List.of(), 0L, 0L)).thenReturn(userResponseDTO);

        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);

        userService.updateUser(savedUser, patchUserDTO);

        verify(passwordEncoder, never()).encode(any());
        verify(userRepository).saveAndFlush(userCaptor.capture());
        assertThat(userCaptor.getValue().getPassword()).isEqualTo(savedUser.getPassword());
    }

    @Test
    @DisplayName("[updateUser] Should Encode And Update Password - When Provided Password Differs From Current Hash")
    void shouldEncodeAndUpdatePasswordWhenProvidedPasswordDiffersFromCurrentHash() {
        PatchUserDTO patchUserDTO = new PatchUserDTO(null, null, "NewPassword123", null, null, null, "Password123");
        when(passwordEncoder.matches("NewPassword123", savedUser.getPassword())).thenReturn(false);
        when(passwordEncoder.matches("Password123", savedUser.getPassword())).thenReturn(true);
        when(passwordEncoder.encode("NewPassword123")).thenReturn("newHashedPassword");
        when(userRepository.saveAndFlush(any(User.class))).thenReturn(savedUser);
        when(userMapper.userToUserResponseDto(savedUser, 0L, 0L, 0L, 0L, 0L, List.of(), List.of(), 0L, 0L)).thenReturn(userResponseDTO);

        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);

        userService.updateUser(savedUser, patchUserDTO);

        verify(userRepository).saveAndFlush(userCaptor.capture());
        assertThat(userCaptor.getValue().getPassword()).isEqualTo("newHashedPassword");
    }

    @Test
    @DisplayName("[updateUser] Should Persist Session Invalidation With Credential Change")
    void shouldPersistSessionInvalidationWithCredentialChange() {
        UUID id = savedUser.getId();
        savedUser.setAuthVersion(4L);
        PatchUserDTO patchUserDTO = new PatchUserDTO(null, null, "NewPassword123", null, null, null, "Password123");
        when(passwordEncoder.matches("NewPassword123", savedUser.getPassword())).thenReturn(false);
        when(passwordEncoder.matches("Password123", savedUser.getPassword())).thenReturn(true);
        when(passwordEncoder.encode("NewPassword123")).thenReturn("newHashedPassword");
        when(userRepository.saveAndFlush(any(User.class))).thenReturn(savedUser);
        when(userMapper.userToUserResponseDto(savedUser, 0L, 0L, 0L, 0L, 0L, List.of(), List.of(), 0L, 0L)).thenReturn(userResponseDTO);

        userService.updateUser(savedUser, patchUserDTO);

        assertThat(savedUser.getAuthVersion()).isEqualTo(5L);
        assertThat(savedUser.getSessionsInvalidatedAt()).isNotNull();
        verify(refreshTokenService).revokeAllRefreshTokens(id);
        verify(refreshTokenService, never()).invalidateAllSessions(id);
    }

    @Test
    @DisplayName("[updateUser] Should Preserve Existing Sessions - Until New Email Is Confirmed")
    void shouldPreserveExistingSessionsUntilNewEmailIsConfirmed() {
        PatchUserDTO patchUserDTO = new PatchUserDTO(null, "new.email@email.com", null, null, null, null, "Password123");
        when(passwordEncoder.matches("Password123", savedUser.getPassword())).thenReturn(true);
        when(userRepository.saveAndFlush(any(User.class))).thenReturn(savedUser);
        when(userMapper.userToUserResponseDto(savedUser, 0L, 0L, 0L, 0L, 0L, List.of(), List.of(), 0L, 0L)).thenReturn(userResponseDTO);

        userService.updateUser(savedUser, patchUserDTO);

        verifyNoInteractions(refreshTokenService);
    }

    @Test
    void shouldApplyPatchToFreshLockedUserInsteadOfRestoringStaleEmailConfirmationState() {
        UUID id = savedUser.getId();
        User staleSnapshot = User.builder().id(id).email("john.doe@email.com")
                .pendingEmail("new.email@email.com").isEmailVerified(true).authVersion(0L)
                .emailConfirmationTokenHash("stale-token-hash")
                .emailConfirmationExpiresAt(LocalDateTime.now().plusMinutes(10)).build();
        User latestPersisted = User.builder().id(id).email("new.email@email.com")
                .pendingEmail(null).isEmailVerified(true).authVersion(1L)
                .emailConfirmationTokenHash(null).emailConfirmationExpiresAt(null)
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build();
        PatchUserDTO patchUserDTO = new PatchUserDTO(null, null, null, "Updated after confirmation", null, null, null);
        when(userRepository.findByIdForUpdate(id)).thenReturn(Optional.of(latestPersisted));
        when(userRepository.saveAndFlush(latestPersisted)).thenReturn(latestPersisted);
        when(userMapper.userToUserResponseDto(eq(latestPersisted), anyLong(), anyLong(), anyLong(), anyLong(),
                anyLong(), any(), any(), anyLong(), anyLong())).thenReturn(userResponseDTO);

        userService.updateUser(staleSnapshot, patchUserDTO);

        assertThat(latestPersisted.getEmail()).isEqualTo("new.email@email.com");
        assertThat(latestPersisted.getPendingEmail()).isNull();
        assertThat(latestPersisted.getEmailConfirmationTokenHash()).isNull();
        assertThat(latestPersisted.getAuthVersion()).isEqualTo(1L);
        assertThat(latestPersisted.getDescription()).isEqualTo("Updated after confirmation");
        verify(userRepository).saveAndFlush(latestPersisted);
        verify(userRepository, never()).saveAndFlush(staleSnapshot);
    }

    @Test
    @DisplayName("[updateUser] Should Not Revoke Any Refresh Token - When Neither Password Nor Email Changes")
    void shouldNotRevokeAnyRefreshTokenWhenNeitherPasswordNorEmailChanges() {
        PatchUserDTO patchUserDTO = new PatchUserDTO(null, null, null, "New bio", null, null, null);
        when(userRepository.saveAndFlush(any(User.class))).thenReturn(savedUser);
        when(userMapper.userToUserResponseDto(savedUser, 0L, 0L, 0L, 0L, 0L, List.of(), List.of(), 0L, 0L)).thenReturn(userResponseDTO);

        userService.updateUser(savedUser, patchUserDTO);

        verifyNoInteractions(refreshTokenService);
    }

    @Test
    @DisplayName("[updateUser] Should Update Description - When A Different Value Is Provided")
    void shouldUpdateDescriptionWhenDifferentValueProvided() {
        PatchUserDTO patchUserDTO = new PatchUserDTO(null, null, null, "New description", null, null, null);
        when(userRepository.saveAndFlush(any(User.class))).thenReturn(savedUser);
        when(userMapper.userToUserResponseDto(savedUser, 0L, 0L, 0L, 0L, 0L, List.of(), List.of(), 0L, 0L)).thenReturn(userResponseDTO);

        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);

        userService.updateUser(savedUser, patchUserDTO);

        verify(userRepository).saveAndFlush(userCaptor.capture());
        assertThat(userCaptor.getValue().getDescription()).isEqualTo("New description");
    }

    @Test
    @DisplayName("[updateUser] Should Update ProfilePicture - When A Different Value Is Provided")
    void shouldUpdateProfilePictureWhenDifferentValueProvided() {
        PatchUserDTO patchUserDTO = new PatchUserDTO(null, null, null, null, "https://new-picture.com/pic.png", null, null);
        when(userRepository.saveAndFlush(any(User.class))).thenReturn(savedUser);
        when(userMapper.userToUserResponseDto(savedUser, 0L, 0L, 0L, 0L, 0L, List.of(), List.of(), 0L, 0L)).thenReturn(userResponseDTO);

        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);

        userService.updateUser(savedUser, patchUserDTO);

        verify(userRepository).saveAndFlush(userCaptor.capture());
        assertThat(userCaptor.getValue().getProfilePicture()).isEqualTo("https://new-picture.com/pic.png");
    }

    @Test
    @DisplayName("[updateUser] Should Update Banner - When A Different Value Is Provided")
    void shouldUpdateBannerWhenDifferentValueProvided() {
        PatchUserDTO patchUserDTO = new PatchUserDTO(null, null, null, null, null, null, null, "https://new-banner.com/banner.png");
        when(userRepository.saveAndFlush(any(User.class))).thenReturn(savedUser);
        when(userMapper.userToUserResponseDto(savedUser, 0L, 0L, 0L, 0L, 0L, List.of(), List.of(), 0L, 0L)).thenReturn(userResponseDTO);

        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);

        userService.updateUser(savedUser, patchUserDTO);

        verify(userRepository).saveAndFlush(userCaptor.capture());
        assertThat(userCaptor.getValue().getBanner()).isEqualTo("https://new-banner.com/banner.png");
    }

    @Test
    @DisplayName("[updateUser] Should Not Change Banner - When The Same Value Is Provided")
    void shouldNotChangeBannerWhenSameValueProvided() {
        savedUser.setBanner("https://picture.com/banner.png");
        PatchUserDTO patchUserDTO = new PatchUserDTO(null, null, null, null, null, null, null, "https://picture.com/banner.png");
        when(userRepository.saveAndFlush(any(User.class))).thenReturn(savedUser);
        when(userMapper.userToUserResponseDto(savedUser, 0L, 0L, 0L, 0L, 0L, List.of(), List.of(), 0L, 0L)).thenReturn(userResponseDTO);

        userService.updateUser(savedUser, patchUserDTO);

        assertThat(savedUser.getBanner()).isEqualTo("https://picture.com/banner.png");
    }

    @Test
    @DisplayName("[updateUser] Should Update PreferredLanguage - When A Different Value Is Provided")
    void shouldUpdatePreferredLanguageWhenDifferentValueProvided() {
        PatchUserDTO patchUserDTO = new PatchUserDTO(null, null, null, null, null, null, null, null, "pt-BR", null);
        when(userRepository.saveAndFlush(any(User.class))).thenReturn(savedUser);
        when(userMapper.userToUserResponseDto(savedUser, 0L, 0L, 0L, 0L, 0L, List.of(), List.of(), 0L, 0L)).thenReturn(userResponseDTO);

        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);

        userService.updateUser(savedUser, patchUserDTO);

        verify(userRepository).saveAndFlush(userCaptor.capture());
        assertThat(userCaptor.getValue().getPreferredLanguage()).isEqualTo("pt-BR");
    }

    @Test
    @DisplayName("[updateUser] Should Not Change PreferredLanguage - When The Same Value Is Provided")
    void shouldNotChangePreferredLanguageWhenSameValueProvided() {
        savedUser.setPreferredLanguage("en-US");
        PatchUserDTO patchUserDTO = new PatchUserDTO(null, null, null, null, null, null, null, null, "en-US", null);
        when(userRepository.saveAndFlush(any(User.class))).thenReturn(savedUser);
        when(userMapper.userToUserResponseDto(savedUser, 0L, 0L, 0L, 0L, 0L, List.of(), List.of(), 0L, 0L)).thenReturn(userResponseDTO);

        userService.updateUser(savedUser, patchUserDTO);

        assertThat(savedUser.getPreferredLanguage()).isEqualTo("en-US");
    }

    @Test
    @DisplayName("[updateUser] Should Throw BadRequestException - When PreferredLanguage Has A Well-Formed But Nonexistent Language Or Region")
    void shouldThrowBadRequestExceptionWhenPreferredLanguageHasAWellFormedButNonexistentLanguageOrRegion() {
        PatchUserDTO patchUserDTO = new PatchUserDTO(null, null, null, null, null, null, null, null, "zz-ZZ", null);

        assertThatThrownBy(() -> userService.updateUser(savedUser, patchUserDTO))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("preferredLanguage must be a real language-region combination, e.g. en-US");

        verify(userRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("[updateUser] Should Update PreferredRegion - When A Different Value Is Provided")
    void shouldUpdatePreferredRegionWhenDifferentValueProvided() {
        PatchUserDTO patchUserDTO = new PatchUserDTO(null, null, null, null, null, null, null, null, null, "BR");
        when(userRepository.saveAndFlush(any(User.class))).thenReturn(savedUser);
        when(userMapper.userToUserResponseDto(savedUser, 0L, 0L, 0L, 0L, 0L, List.of(), List.of(), 0L, 0L)).thenReturn(userResponseDTO);

        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);

        userService.updateUser(savedUser, patchUserDTO);

        verify(userRepository).saveAndFlush(userCaptor.capture());
        assertThat(userCaptor.getValue().getPreferredRegion()).isEqualTo("BR");
    }

    @Test
    @DisplayName("[updateUser] Should Not Change PreferredRegion - When The Same Value Is Provided")
    void shouldNotChangePreferredRegionWhenSameValueProvided() {
        savedUser.setPreferredRegion("US");
        PatchUserDTO patchUserDTO = new PatchUserDTO(null, null, null, null, null, null, null, null, null, "US");
        when(userRepository.saveAndFlush(any(User.class))).thenReturn(savedUser);
        when(userMapper.userToUserResponseDto(savedUser, 0L, 0L, 0L, 0L, 0L, List.of(), List.of(), 0L, 0L)).thenReturn(userResponseDTO);

        userService.updateUser(savedUser, patchUserDTO);

        assertThat(savedUser.getPreferredRegion()).isEqualTo("US");
    }

    @Test
    @DisplayName("[updateUser] Should Throw BadRequestException - When PreferredRegion Is Well-Formed But Nonexistent")
    void shouldThrowBadRequestExceptionWhenPreferredRegionIsWellFormedButNonexistent() {
        PatchUserDTO patchUserDTO = new PatchUserDTO(null, null, null, null, null, null, null, null, null, "ZZ");

        assertThatThrownBy(() -> userService.updateUser(savedUser, patchUserDTO))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("preferredRegion must be a real ISO 3166-1 alpha-2 code, e.g. US");

        verify(userRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("[updateUser] Should Update IsProfilePublic - When A Different Value Is Provided")
    void shouldUpdateIsProfilePublicWhenDifferentValueProvided() {
        PatchUserDTO patchUserDTO = new PatchUserDTO(null, null, null, null, null, false, null);
        when(userRepository.saveAndFlush(any(User.class))).thenReturn(savedUser);
        when(userMapper.userToUserResponseDto(savedUser, 0L, 0L, 0L, 0L, 0L, List.of(), List.of(), 0L, 0L)).thenReturn(userResponseDTO);

        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);

        userService.updateUser(savedUser, patchUserDTO);

        verify(userRepository).saveAndFlush(userCaptor.capture());
        assertThat(userCaptor.getValue().getIsProfilePublic()).isFalse();
        verifyNoInteractions(followerService);
    }

    @Test
    @DisplayName("[updateUser] Should Accept All Pending Follow Requests - When Profile Becomes Public")
    void shouldAcceptAllPendingFollowRequestsWhenProfileBecomesPublic() {
        UUID id = savedUser.getId();
        savedUser.setIsProfilePublic(false);
        PatchUserDTO patchUserDTO = new PatchUserDTO(null, null, null, null, null, true, null);
        when(userRepository.saveAndFlush(any(User.class))).thenReturn(savedUser);
        when(userMapper.userToUserResponseDto(savedUser, 0L, 0L, 0L, 0L, 0L, List.of(), List.of(), 0L, 0L)).thenReturn(userResponseDTO);

        userService.updateUser(savedUser, patchUserDTO);

        InOrder order = inOrder(userRepository, followerService);
        order.verify(userRepository).saveAndFlush(savedUser);
        order.verify(followerService).acceptAllPendingFollowRequestsFor(savedUser);
    }

    @Test
    @DisplayName("[updateUser] Should Not Accept Pending Follow Requests - When Profile Is Already Public")
    void shouldNotAcceptPendingFollowRequestsWhenProfileIsAlreadyPublic() {
        savedUser.setIsProfilePublic(true);
        PatchUserDTO patchUserDTO = new PatchUserDTO(null, null, null, null, null, true, null);
        when(userRepository.saveAndFlush(any(User.class))).thenReturn(savedUser);
        when(userMapper.userToUserResponseDto(savedUser, 0L, 0L, 0L, 0L, 0L, List.of(), List.of(), 0L, 0L)).thenReturn(userResponseDTO);

        userService.updateUser(savedUser, patchUserDTO);

        verifyNoInteractions(followerService);
    }

    @Test
    @DisplayName("[updateUser] Should Throw ConflictException With Username Message - When Update Violates Username Constraint")
    void shouldThrowConflictExceptionWithUsernameMessageWhenUpdateViolatesUsernameConstraint() {
        PatchUserDTO patchUserDTO = new PatchUserDTO("TakenUsername", null, null, null, null, null, null);
        when(userRepository.saveAndFlush(any(User.class)))
                .thenThrow(buildDataIntegrityViolationException("uq_users_username"));

        assertThatThrownBy(() -> userService.updateUser(savedUser, patchUserDTO))
                .isInstanceOf(ConflictException.class)
                .hasMessage("Username already in use");

        verify(userMapper, never()).userToUserResponseDto(any(), anyLong(), anyLong(), anyLong(), anyLong(), anyLong(), any(), any(), anyLong(), anyLong());
    }

    @Test
    @DisplayName("[updateUser] Should Throw ConflictException With Email Message - When Update Violates Email Constraint")
    void shouldThrowConflictExceptionWithEmailMessageWhenUpdateViolatesEmailConstraint() {
        PatchUserDTO patchUserDTO = new PatchUserDTO(null, "taken@email.com", null, null, null, null, "Password123");
        when(passwordEncoder.matches("Password123", savedUser.getPassword())).thenReturn(true);
        when(userRepository.saveAndFlush(any(User.class)))
                .thenThrow(buildDataIntegrityViolationException("uq_users_email"));

        assertThatThrownBy(() -> userService.updateUser(savedUser, patchUserDTO))
                .isInstanceOf(ConflictException.class)
                .hasMessage("Email already in use");

        verify(userMapper, never()).userToUserResponseDto(any(), anyLong(), anyLong(), anyLong(), anyLong(), anyLong(), any(), any(), anyLong(), anyLong());
    }

    @Test
    @DisplayName("[updateUser] Should Throw BadRequestException - When Changing Password Without CurrentPassword")
    void shouldThrowBadRequestExceptionWhenChangingPasswordWithoutCurrentPassword() {
        PatchUserDTO patchUserDTO = new PatchUserDTO(null, null, "NewPassword123", null, null, null, null);
        when(passwordEncoder.matches("NewPassword123", savedUser.getPassword())).thenReturn(false);

        assertThatThrownBy(() -> userService.updateUser(savedUser, patchUserDTO))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("currentPassword must be provided to change password or email");

        verify(userRepository, never()).save(any());
    }

    @Test
    @DisplayName("[updateUser] Should Throw UnauthorizedException - When Changing Password With Wrong CurrentPassword")
    void shouldThrowUnauthorizedExceptionWhenChangingPasswordWithWrongCurrentPassword() {
        PatchUserDTO patchUserDTO = new PatchUserDTO(null, null, "NewPassword123", null, null, null, "WrongCurrentPass1");
        when(passwordEncoder.matches("NewPassword123", savedUser.getPassword())).thenReturn(false);
        when(passwordEncoder.matches("WrongCurrentPass1", savedUser.getPassword())).thenReturn(false);

        assertThatThrownBy(() -> userService.updateUser(savedUser, patchUserDTO))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessage("Invalid password");

        verify(userRepository, never()).save(any());
    }

    @Test
    @DisplayName("[updateUser] Should Throw BadRequestException - When Changing Email Without CurrentPassword")
    void shouldThrowBadRequestExceptionWhenChangingEmailWithoutCurrentPassword() {
        PatchUserDTO patchUserDTO = new PatchUserDTO(null, "new.email@email.com", null, null, null, null, null);

        assertThatThrownBy(() -> userService.updateUser(savedUser, patchUserDTO))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("currentPassword must be provided to change password or email");

        verify(userRepository, never()).save(any());
    }

    @Test
    @DisplayName("[updateUser] Should Throw UnauthorizedException - When Changing Email With Wrong CurrentPassword")
    void shouldThrowUnauthorizedExceptionWhenChangingEmailWithWrongCurrentPassword() {
        PatchUserDTO patchUserDTO = new PatchUserDTO(null, "new.email@email.com", null, null, null, null, "WrongCurrentPass1");
        when(passwordEncoder.matches("WrongCurrentPass1", savedUser.getPassword())).thenReturn(false);

        assertThatThrownBy(() -> userService.updateUser(savedUser, patchUserDTO))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessage("Invalid password");

        verify(userRepository, never()).save(any());
    }

    @Test
    @DisplayName("[checkCredentialChanges] Should Throw NotFoundException - When User Does Not Exist")
    void shouldThrowNotFoundExceptionWhenCheckingCredentialChangesForUserThatDoesNotExist() {
        UUID id = UUID.randomUUID();
        PatchUserDTO patchUserDTO = new PatchUserDTO(null, null, null, null, null, null, null);
        when(userRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userService.checkCredentialChanges(id, patchUserDTO))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("User not found");
    }

    @Test
    @DisplayName("[checkCredentialChanges] Should Return The Loaded User And False - When No Field Is Provided")
    void shouldReturnFalseWhenCheckingCredentialChangesWithNoFieldProvided() {
        UUID id = savedUser.getId();
        PatchUserDTO patchUserDTO = new PatchUserDTO(null, null, null, "Updated bio", null, null, null);
        when(userRepository.findById(id)).thenReturn(Optional.of(savedUser));

        UserService.CredentialCheck result = userService.checkCredentialChanges(id, patchUserDTO);

        assertThat(result.touchesCredentials()).isFalse();
        assertThat(result.user()).isEqualTo(savedUser);
    }

    @Test
    @DisplayName("[checkCredentialChanges] Should Return False - When Provided Email Matches Current Email")
    void shouldReturnFalseWhenCheckingCredentialChangesWithEmailMatchingCurrentEmail() {
        UUID id = savedUser.getId();
        PatchUserDTO patchUserDTO = new PatchUserDTO(null, "  " + savedUser.getEmail().toUpperCase() + "  ", null, null, null, null, null);
        when(userRepository.findById(id)).thenReturn(Optional.of(savedUser));

        UserService.CredentialCheck result = userService.checkCredentialChanges(id, patchUserDTO);

        assertThat(result.touchesCredentials()).isFalse();
    }

    @Test
    @DisplayName("[checkCredentialChanges] Should Return True - When Provided Email Differs From Current Email")
    void shouldReturnTrueWhenCheckingCredentialChangesWithEmailDifferentFromCurrentEmail() {
        UUID id = savedUser.getId();
        PatchUserDTO patchUserDTO = new PatchUserDTO(null, "new.email@email.com", null, null, null, null, null);
        when(userRepository.findById(id)).thenReturn(Optional.of(savedUser));

        UserService.CredentialCheck result = userService.checkCredentialChanges(id, patchUserDTO);

        assertThat(result.touchesCredentials()).isTrue();
    }

    @Test
    @DisplayName("[checkCredentialChanges] Should Return False - When Provided Password Matches Current Hash")
    void shouldReturnFalseWhenCheckingCredentialChangesWithPasswordMatchingCurrentHash() {
        UUID id = savedUser.getId();
        PatchUserDTO patchUserDTO = new PatchUserDTO(null, null, "SamePassword123", null, null, null, null);
        when(userRepository.findById(id)).thenReturn(Optional.of(savedUser));
        when(passwordEncoder.matches("SamePassword123", savedUser.getPassword())).thenReturn(true);

        UserService.CredentialCheck result = userService.checkCredentialChanges(id, patchUserDTO);

        assertThat(result.touchesCredentials()).isFalse();
    }

    @Test
    @DisplayName("[checkCredentialChanges] Should Return True - When Provided Password Differs From Current Hash")
    void shouldReturnTrueWhenCheckingCredentialChangesWithPasswordDifferentFromCurrentHash() {
        UUID id = savedUser.getId();
        PatchUserDTO patchUserDTO = new PatchUserDTO(null, null, "NewPassword123", null, null, null, null);
        when(userRepository.findById(id)).thenReturn(Optional.of(savedUser));
        when(passwordEncoder.matches("NewPassword123", savedUser.getPassword())).thenReturn(false);

        UserService.CredentialCheck result = userService.checkCredentialChanges(id, patchUserDTO);

        assertThat(result.touchesCredentials()).isTrue();
    }

    @Test
    @DisplayName("[login] Should Return UserResponseDTO - When Credentials Are Valid Using Email")
    void shouldReturnUserResponseDtoWhenCredentialsAreValidUsingEmail() {
        LoginUserDTO loginUserDTO = new LoginUserDTO(savedUser.getEmail(), "Password123");
        when(userRepository.findByUsernameIgnoreCase(savedUser.getEmail())).thenReturn(Optional.empty());
        when(userRepository.findByEmailIgnoreCase(savedUser.getEmail())).thenReturn(Optional.of(savedUser));
        when(passwordEncoder.matches("Password123", savedUser.getPassword())).thenReturn(true);
        when(userMapper.userToUserResponseDto(savedUser, 0L, 0L, 0L, 0L, 0L, List.of(), List.of(), 0L, 0L)).thenReturn(userResponseDTO);

        UserResponseDTO result = userService.login(loginUserDTO);

        assertThat(result).isEqualTo(userResponseDTO);
    }

    @Test
    @DisplayName("[login] Should Return UserResponseDTO - When Credentials Are Valid Using Username")
    void shouldReturnUserResponseDtoWhenCredentialsAreValidUsingUsername() {
        LoginUserDTO loginUserDTO = new LoginUserDTO(savedUser.getUsername(), "Password123");
        when(userRepository.findByUsernameIgnoreCase(savedUser.getUsername())).thenReturn(Optional.of(savedUser));
        when(passwordEncoder.matches("Password123", savedUser.getPassword())).thenReturn(true);
        when(userMapper.userToUserResponseDto(savedUser, 0L, 0L, 0L, 0L, 0L, List.of(), List.of(), 0L, 0L)).thenReturn(userResponseDTO);

        UserResponseDTO result = userService.login(loginUserDTO);

        assertThat(result).isEqualTo(userResponseDTO);
        verify(userRepository, never()).findByEmailIgnoreCase(any());
    }

    @Test
    @DisplayName("[login] Should Trim Identifier Before Lookup - When Identifier Has Surrounding Whitespace")
    void shouldTrimIdentifierBeforeLookupWhenIdentifierHasSurroundingWhitespace() {
        LoginUserDTO loginUserDTO = new LoginUserDTO("  " + savedUser.getEmail() + "  ", "Password123");
        when(userRepository.findByUsernameIgnoreCase(savedUser.getEmail())).thenReturn(Optional.empty());
        when(userRepository.findByEmailIgnoreCase(savedUser.getEmail())).thenReturn(Optional.of(savedUser));
        when(passwordEncoder.matches("Password123", savedUser.getPassword())).thenReturn(true);
        when(userMapper.userToUserResponseDto(savedUser, 0L, 0L, 0L, 0L, 0L, List.of(), List.of(), 0L, 0L)).thenReturn(userResponseDTO);

        UserResponseDTO result = userService.login(loginUserDTO);

        assertThat(result).isEqualTo(userResponseDTO);
        verify(userRepository).findByEmailIgnoreCase(savedUser.getEmail());
    }

    @Test
    @DisplayName("[login] Should Return UserResponseDTO - When Email Is Verified")
    void shouldReturnUserResponseDtoWhenEmailIsVerified() {
        savedUser.setIsEmailVerified(true);
        LoginUserDTO loginUserDTO = new LoginUserDTO(savedUser.getEmail(), "Password123");
        when(userRepository.findByUsernameIgnoreCase(savedUser.getEmail())).thenReturn(Optional.empty());
        when(userRepository.findByEmailIgnoreCase(savedUser.getEmail())).thenReturn(Optional.of(savedUser));
        when(passwordEncoder.matches("Password123", savedUser.getPassword())).thenReturn(true);
        when(userMapper.userToUserResponseDto(savedUser, 0L, 0L, 0L, 0L, 0L, List.of(), List.of(), 0L, 0L)).thenReturn(userResponseDTO);

        UserResponseDTO result = userService.login(loginUserDTO);

        assertThat(result).isEqualTo(userResponseDTO);
    }

    @Test
    @DisplayName("[login] Should Throw ForbiddenException - When Email Is Not Verified")
    void shouldThrowForbiddenExceptionWhenEmailIsNotVerified() {
        savedUser.setIsEmailVerified(false);
        LoginUserDTO loginUserDTO = new LoginUserDTO(savedUser.getEmail(), "Password123");
        when(userRepository.findByUsernameIgnoreCase(savedUser.getEmail())).thenReturn(Optional.empty());
        when(userRepository.findByEmailIgnoreCase(savedUser.getEmail())).thenReturn(Optional.of(savedUser));
        when(passwordEncoder.matches("Password123", savedUser.getPassword())).thenReturn(true);

        assertThatThrownBy(() -> userService.login(loginUserDTO))
                .isInstanceOf(ForbiddenException.class)
                .hasMessage("Email not verified");

        verifyNoInteractions(userMapper);
    }

    @Test
    @DisplayName("[login] Should Throw UnauthorizedException - When Identifier Does Not Match Any User")
    void shouldThrowUnauthorizedExceptionWhenIdentifierDoesNotMatchAnyUser() {
        LoginUserDTO loginUserDTO = new LoginUserDTO("unknown@email.com", "Password123");
        when(userRepository.findByUsernameIgnoreCase("unknown@email.com")).thenReturn(Optional.empty());
        when(userRepository.findByEmailIgnoreCase("unknown@email.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userService.login(loginUserDTO))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessage("Invalid credentials");

        verifyNoInteractions(userMapper);
    }

    @Test
    @DisplayName("[login] Should Compare Against A Dummy Hash To Equalize Timing - When Identifier Does Not Match Any User")
    void shouldCompareAgainstADummyHashToEqualizeTimingWhenIdentifierDoesNotMatchAnyUser() {
        LoginUserDTO loginUserDTO = new LoginUserDTO("unknown@email.com", "Password123");
        when(userRepository.findByUsernameIgnoreCase("unknown@email.com")).thenReturn(Optional.empty());
        when(userRepository.findByEmailIgnoreCase("unknown@email.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userService.login(loginUserDTO)).isInstanceOf(UnauthorizedException.class);

        verify(passwordEncoder).matches("Password123", UserServiceImpl.DUMMY_PASSWORD_HASH);
    }

    @Test
    @DisplayName("[login] Should Throw UnauthorizedException - When Password Does Not Match")
    void shouldThrowUnauthorizedExceptionWhenPasswordDoesNotMatch() {
        LoginUserDTO loginUserDTO = new LoginUserDTO(savedUser.getEmail(), "WrongPassword123");
        when(userRepository.findByUsernameIgnoreCase(savedUser.getEmail())).thenReturn(Optional.empty());
        when(userRepository.findByEmailIgnoreCase(savedUser.getEmail())).thenReturn(Optional.of(savedUser));
        when(passwordEncoder.matches("WrongPassword123", savedUser.getPassword())).thenReturn(false);

        assertThatThrownBy(() -> userService.login(loginUserDTO))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessage("Invalid credentials");

        verifyNoInteractions(userMapper);
    }

    @Test
    @DisplayName("[findByEmail] Should Return UserResponseDTO - When Email Exists")
    void shouldReturnUserResponseDtoWhenEmailExists() {
        when(userRepository.findByEmailIgnoreCase(savedUser.getEmail())).thenReturn(Optional.of(savedUser));
        when(userMapper.userToUserResponseDto(savedUser, 0L, 0L, 0L, 0L, 0L, List.of(), List.of(), 0L, 0L)).thenReturn(userResponseDTO);

        Optional<UserResponseDTO> result = userService.findByEmail(savedUser.getEmail());

        assertThat(result).contains(userResponseDTO);
    }

    @Test
    @DisplayName("[findByEmail] Should Hide An Unconfirmed Account - When OAuth Resolves Its Email")
    void shouldHideAnUnconfirmedAccountWhenOauthResolvesItsEmail() {
        savedUser.setIsEmailVerified(false);
        when(userRepository.findByEmailIgnoreCase(savedUser.getEmail())).thenReturn(Optional.of(savedUser));

        Optional<UserResponseDTO> result = userService.findByEmail(savedUser.getEmail());

        assertThat(result).isEmpty();
        verifyNoInteractions(userMapper);
    }

    @Test
    @DisplayName("[findByEmail] Should Return Empty - When Email Does Not Exist")
    void shouldReturnEmptyWhenEmailDoesNotExist() {
        when(userRepository.findByEmailIgnoreCase("unknown@email.com")).thenReturn(Optional.empty());

        Optional<UserResponseDTO> result = userService.findByEmail("unknown@email.com");

        assertThat(result).isEmpty();
        verifyNoInteractions(userMapper);
    }

    @Test
    @DisplayName("[findByEmail] Should Trim Email Before Lookup - When Email Has Surrounding Whitespace")
    void shouldTrimEmailBeforeLookupWhenEmailHasSurroundingWhitespace() {
        when(userRepository.findByEmailIgnoreCase(savedUser.getEmail())).thenReturn(Optional.of(savedUser));
        when(userMapper.userToUserResponseDto(savedUser, 0L, 0L, 0L, 0L, 0L, List.of(), List.of(), 0L, 0L)).thenReturn(userResponseDTO);

        userService.findByEmail("  " + savedUser.getEmail() + "  ");

        verify(userRepository).findByEmailIgnoreCase(savedUser.getEmail());
    }

    private DataIntegrityViolationException buildDataIntegrityViolationException(String constraintName) {
        ConstraintViolationException cve = new ConstraintViolationException(
                "constraint violated",
                null,
                constraintName
        );
        return new DataIntegrityViolationException("db error", cve);
    }
}
