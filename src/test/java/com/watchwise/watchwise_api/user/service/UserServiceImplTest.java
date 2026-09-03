package com.watchwise.watchwise_api.user.service.impl;

import com.watchwise.watchwise_api.auth.service.RefreshTokenService;
import com.watchwise.watchwise_api.common.exception.BadRequestException;
import com.watchwise.watchwise_api.common.exception.ConflictException;
import com.watchwise.watchwise_api.common.exception.ForbiddenException;
import com.watchwise.watchwise_api.common.exception.NotFoundException;
import com.watchwise.watchwise_api.common.exception.UnauthorizedException;
import com.watchwise.watchwise_api.common.dto.GenreCountDTO;
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
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
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
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();

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
                List.of(),
                List.of(),
                null,
                0L,
                0L
        );

        lenient().when(followerRepository.countByFollowedIdAndStatus(any(), any())).thenReturn(0L);
        lenient().when(followerRepository.countByFollowerIdAndStatus(any(), any())).thenReturn(0L);
    }

    @Test
    @DisplayName("[saveNewUser] Should Return UserResponseDTO - When Save Is Successful")
    void shouldReturnUserResponseDtoWhenSaveIsSuccessful() {
        when(userMapper.postUserDtoToUser(postUserDTO)).thenReturn(mappedUser);
        when(passwordEncoder.encode(postUserDTO.password())).thenReturn("hashedPassword");
        when(userRepository.save(any(User.class))).thenReturn(savedUser);
        when(userMapper.userToUserResponseDto(savedUser, 0L, 0L, 0L, List.of(), List.of(), 0L, 0L)).thenReturn(userResponseDTO);

        UserResponseDTO result = userService.saveNewUser(postUserDTO);

        assertThat(result).isEqualTo(userResponseDTO);
        verify(userRepository).save(any(User.class));
        verify(userMapper).userToUserResponseDto(savedUser, 0L, 0L, 0L, List.of(), List.of(), 0L, 0L);
    }

    @Test
    @DisplayName("[saveNewUser] Should Normalize Email, Username And Password - When Data Is Valid")
    void shouldNormalizeEmailUsernameAndPasswordBeforeSavingWhenDataIsValid() {
        when(userMapper.postUserDtoToUser(postUserDTO)).thenReturn(mappedUser);
        when(passwordEncoder.encode(postUserDTO.password())).thenReturn("hashedPassword");
        when(userRepository.save(any(User.class))).thenReturn(savedUser);
        when(userMapper.userToUserResponseDto(savedUser, 0L, 0L, 0L, List.of(), List.of(), 0L, 0L)).thenReturn(userResponseDTO);

        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);

        userService.saveNewUser(postUserDTO);

        verify(userRepository).save(userCaptor.capture());
        User capturedUser = userCaptor.getValue();

        assertThat(capturedUser.getUsername()).isEqualTo("JohnDoe");
        assertThat(capturedUser.getEmail()).isEqualTo("john.doe@email.com");
        assertThat(capturedUser.getPassword()).isEqualTo("hashedPassword");
    }

    @Test
    @DisplayName("[saveNewUser] Should Set IsEmailVerified True - When Registering Common Account")
    void shouldSetIsEmailVerifiedTrueWhenRegisteringCommonAccount() {
        when(userMapper.postUserDtoToUser(postUserDTO)).thenReturn(mappedUser);
        when(passwordEncoder.encode(postUserDTO.password())).thenReturn("hashedPassword");
        when(userRepository.save(any(User.class))).thenReturn(savedUser);
        when(userMapper.userToUserResponseDto(savedUser, 0L, 0L, 0L, List.of(), List.of(), 0L, 0L)).thenReturn(userResponseDTO);

        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);

        userService.saveNewUser(postUserDTO);

        verify(userRepository).save(userCaptor.capture());
        assertThat(userCaptor.getValue().getIsEmailVerified()).isTrue();
    }

    @Test
    @DisplayName("[saveNewUser] Should Throw ConflictException With Username Message - When Username Constraint Is Violated")
    void shouldThrowConflictExceptionWithUsernameMessageWhenUsernameConstraintIsViolated() {
        when(userMapper.postUserDtoToUser(postUserDTO)).thenReturn(mappedUser);
        when(userRepository.save(any(User.class)))
                .thenThrow(buildDataIntegrityViolationException("uq_users_username"));

        assertThatThrownBy(() -> userService.saveNewUser(postUserDTO))
                .isInstanceOf(ConflictException.class)
                .hasMessage("Username already in use");

        verify(userMapper, never()).userToUserResponseDto(any(), anyLong(), anyLong(), anyLong(), any(), any(), anyLong(), anyLong());
    }

    @Test
    @DisplayName("[saveNewUser] Should Throw ConflictException With Email Message - When Email Constraint Is Violated")
    void shouldThrowConflictExceptionWithEmailMessageWhenEmailConstraintIsViolated() {
        when(userMapper.postUserDtoToUser(postUserDTO)).thenReturn(mappedUser);
        when(userRepository.save(any(User.class)))
                .thenThrow(buildDataIntegrityViolationException("uq_users_email"));

        assertThatThrownBy(() -> userService.saveNewUser(postUserDTO))
                .isInstanceOf(ConflictException.class)
                .hasMessage("Email already in use");

        verify(userMapper, never()).userToUserResponseDto(any(), anyLong(), anyLong(), anyLong(), any(), any(), anyLong(), anyLong());
    }

    @Test
    @DisplayName("[saveNewUser] Should Throw ConflictException With Generic Message - When Constraint Name Is Unknown")
    void shouldThrowConflictExceptionWithGenericMessageWhenConstraintNameIsUnknown() {
        when(userMapper.postUserDtoToUser(postUserDTO)).thenReturn(mappedUser);
        when(userRepository.save(any(User.class)))
                .thenThrow(buildDataIntegrityViolationException("uq_some_other_constraint"));

        assertThatThrownBy(() -> userService.saveNewUser(postUserDTO))
                .isInstanceOf(ConflictException.class)
                .hasMessage("Username or email already in use");

        verify(userMapper, never()).userToUserResponseDto(any(), anyLong(), anyLong(), anyLong(), any(), any(), anyLong(), anyLong());
    }

    @Test
    @DisplayName("[saveNewUser] Should Throw ConflictException With Generic Message - When Cause Is Not ConstraintViolationException")
    void shouldThrowConflictExceptionWithGenericMessageWhenCauseIsNotConstraintViolationException() {
        when(userMapper.postUserDtoToUser(postUserDTO)).thenReturn(mappedUser);

        DataIntegrityViolationException exception =
                new DataIntegrityViolationException("generic db error", new RuntimeException("unexpected cause"));
        when(userRepository.save(any(User.class))).thenThrow(exception);

        assertThatThrownBy(() -> userService.saveNewUser(postUserDTO))
                .isInstanceOf(ConflictException.class)
                .hasMessage("Username or email already in use");

        verify(userMapper, never()).userToUserResponseDto(any(), anyLong(), anyLong(), anyLong(), any(), any(), anyLong(), anyLong());
    }

    @Test
    @DisplayName("[saveNewUser] Should Throw ConflictException With Generic Message - When Cause Is Null")
    void shouldThrowConflictExceptionWithGenericMessageWhenCauseIsNull() {
        when(userMapper.postUserDtoToUser(postUserDTO)).thenReturn(mappedUser);

        DataIntegrityViolationException exception = new DataIntegrityViolationException("no cause here");
        when(userRepository.save(any(User.class))).thenThrow(exception);

        assertThatThrownBy(() -> userService.saveNewUser(postUserDTO))
                .isInstanceOf(ConflictException.class)
                .hasMessage("Username or email already in use");

        verify(userMapper, never()).userToUserResponseDto(any(), anyLong(), anyLong(), anyLong(), any(), any(), anyLong(), anyLong());
    }

    @Test
    @DisplayName("[saveNewUser] Should Throw BadRequestException - When Trimmed Username Is Shorter Than The Minimum Length")
    void shouldThrowBadRequestExceptionWhenTrimmedUsernameIsShorterThanTheMinimumLength() {
        PostUserDTO postUserDTOWithPaddedShortUsername = new PostUserDTO(
                " ab ",
                postUserDTO.email(),
                postUserDTO.password(),
                postUserDTO.description(),
                postUserDTO.profilePicture(),
                postUserDTO.isProfilePublic()
        );

        assertThatThrownBy(() -> userService.saveNewUser(postUserDTOWithPaddedShortUsername))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("Username must be at least 3 characters long");

        verify(userRepository, never()).save(any());
    }

    @Test
    @DisplayName("[getUserById] Should Return PublicUserProfileDTO - When Id Exists And Profile Is Public")
    void shouldReturnPublicUserDtoWhenIdExistsAndProfileIsPublic() {
        UUID id = savedUser.getId();
        when(userRepository.findById(id)).thenReturn(Optional.of(savedUser));
        when(userMapper.userToPublicUserProfileDto(savedUser, 0L, 0L, 0L, List.of(), List.of(), 0L, 0L)).thenReturn(publicUserDTO);

        PublicUserProfileDTO result = userService.getUserById(id);

        assertThat(result).isEqualTo(publicUserDTO);
        verify(userRepository).findById(id);
        verify(userMapper).userToPublicUserProfileDto(savedUser, 0L, 0L, 0L, List.of(), List.of(), 0L, 0L);
    }

    @Test
    @DisplayName("[getUserById] Should Throw NotFoundException - When Id Does Not Exist")
    void shouldThrowNotFoundExceptionWhenIdDoesNotExist() {
        UUID id = UUID.randomUUID();
        when(userRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userService.getUserById(id))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("User not found");

        verify(userMapper, never()).userToPublicUserProfileDto(any(), anyLong(), anyLong(), anyLong(), any(), any(), anyLong(), anyLong());
    }

    @Test
    @DisplayName("[getUserById] Should Throw ForbiddenException - When Profile Is Private")
    void shouldThrowForbiddenExceptionWhenProfileIsPrivate() {
        UUID id = savedUser.getId();
        savedUser.setIsProfilePublic(false);
        when(userRepository.findById(id)).thenReturn(Optional.of(savedUser));

        assertThatThrownBy(() -> userService.getUserById(id))
                .isInstanceOf(ForbiddenException.class)
                .hasMessage("This user profile is private");

        verify(userMapper, never()).userToPublicUserProfileDto(any(), anyLong(), anyLong(), anyLong(), any(), any(), anyLong(), anyLong());
    }

    @Test
    @DisplayName("[getCurrentUser] Should Return UserResponseDTO - When Id Exists")
    void shouldReturnUserResponseDtoWhenIdExists() {
        UUID id = savedUser.getId();
        when(userRepository.findById(id)).thenReturn(Optional.of(savedUser));
        when(userMapper.userToUserResponseDto(savedUser, 0L, 0L, 0L, List.of(), List.of(), 0L, 0L)).thenReturn(userResponseDTO);

        UserResponseDTO result = userService.getCurrentUser(id);

        assertThat(result).isEqualTo(userResponseDTO);
        verify(userRepository).findById(id);
        verify(userMapper).userToUserResponseDto(savedUser, 0L, 0L, 0L, List.of(), List.of(), 0L, 0L);
    }

    @Test
    @DisplayName("[getCurrentUser] Should Return UserResponseDTO - When Profile Is Private")
    void shouldReturnUserResponseDtoWhenProfileIsPrivate() {
        UUID id = savedUser.getId();
        savedUser.setIsProfilePublic(false);
        when(userRepository.findById(id)).thenReturn(Optional.of(savedUser));
        when(userMapper.userToUserResponseDto(savedUser, 0L, 0L, 0L, List.of(), List.of(), 0L, 0L)).thenReturn(userResponseDTO);

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
        when(diaryEntryRepository.sumRuntimeMinutesByUserId(id)).thenReturn(4200L);
        when(diaryEntryRepository.sumRuntimeMinutesByUserIdAndWatchedDateBetween(eq(id), any(), any())).thenReturn(300L);
        when(diaryEntryRepository.countDistinctTitlesByGenreAndUserIdForMovies(id)).thenReturn(List.of(actionGenre));
        when(userMapper.userToUserResponseDto(eq(savedUser), anyLong(), anyLong(), anyLong(), any(), any(), anyLong(), anyLong())).thenReturn(userResponseDTO);

        ArgumentCaptor<Long> totalCaptor = ArgumentCaptor.forClass(Long.class);
        ArgumentCaptor<Long> last30Captor = ArgumentCaptor.forClass(Long.class);
        ArgumentCaptor<List<GenreCountDTO>> genreMoviesCaptor = ArgumentCaptor.forClass(List.class);

        userService.getCurrentUser(id);

        verify(userMapper).userToUserResponseDto(eq(savedUser), totalCaptor.capture(), last30Captor.capture(), anyLong(),
                genreMoviesCaptor.capture(), any(), anyLong(), anyLong());
        assertThat(totalCaptor.getValue()).isEqualTo(4200L);
        assertThat(last30Captor.getValue()).isEqualTo(300L);
        assertThat(genreMoviesCaptor.getValue()).containsExactly(new GenreCountDTO("Action", 5L));
    }

    @Test
    @DisplayName("[getCurrentUser] Should Propagate Computed Followers And Following Counts To The Mapper - When User Has Followers")
    void shouldPropagateComputedFollowersAndFollowingCountsToTheMapperWhenUserHasFollowers() {
        UUID id = savedUser.getId();
        when(userRepository.findById(id)).thenReturn(Optional.of(savedUser));
        when(followerRepository.countByFollowedIdAndStatus(id, FollowStatus.ACCEPTED)).thenReturn(42L);
        when(followerRepository.countByFollowerIdAndStatus(id, FollowStatus.ACCEPTED)).thenReturn(17L);
        when(userMapper.userToUserResponseDto(eq(savedUser), anyLong(), anyLong(), anyLong(), any(), any(), anyLong(), anyLong())).thenReturn(userResponseDTO);

        ArgumentCaptor<Long> followersCaptor = ArgumentCaptor.forClass(Long.class);
        ArgumentCaptor<Long> followingCaptor = ArgumentCaptor.forClass(Long.class);

        userService.getCurrentUser(id);

        verify(userMapper).userToUserResponseDto(eq(savedUser), anyLong(), anyLong(), anyLong(), any(), any(), followersCaptor.capture(), followingCaptor.capture());
        assertThat(followersCaptor.getValue()).isEqualTo(42L);
        assertThat(followingCaptor.getValue()).isEqualTo(17L);
    }

    @Test
    @DisplayName("[saveNewUser] Should Not Query Watch Stats - When Registering A New User")
    void shouldNotQueryWatchStatsWhenRegisteringANewUser() {
        when(userMapper.postUserDtoToUser(postUserDTO)).thenReturn(mappedUser);
        when(passwordEncoder.encode(postUserDTO.password())).thenReturn("hashedPassword");
        when(userRepository.save(any(User.class))).thenReturn(savedUser);
        when(userMapper.userToUserResponseDto(savedUser, 0L, 0L, 0L, List.of(), List.of(), 0L, 0L)).thenReturn(userResponseDTO);

        userService.saveNewUser(postUserDTO);

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

        verify(userMapper, never()).userToUserResponseDto(any(), anyLong(), anyLong(), anyLong(), any(), any(), anyLong(), anyLong());
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
        when(userMapper.userToUserResponseDto(savedUser, 0L, 0L, 0L, List.of(), List.of(), 0L, 0L)).thenReturn(userResponseDTO);

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
        when(userMapper.userToUserResponseDto(savedUser, 0L, 0L, 0L, List.of(), List.of(), 0L, 0L)).thenReturn(userResponseDTO);

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
        when(userMapper.userToUserResponseDto(savedUser, 0L, 0L, 0L, List.of(), List.of(), 0L, 0L)).thenReturn(userResponseDTO);

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
    @DisplayName("[updateUser] Should Update Email Normalized - When A Different Value Is Provided")
    void shouldUpdateEmailNormalizedWhenDifferentValueProvided() {
        PatchUserDTO patchUserDTO = new PatchUserDTO(null, "  NEW.EMAIL@EMAIL.COM  ", null, null, null, null, "Password123");
        when(passwordEncoder.matches("Password123", savedUser.getPassword())).thenReturn(true);
        when(userRepository.saveAndFlush(any(User.class))).thenReturn(savedUser);
        when(userMapper.userToUserResponseDto(savedUser, 0L, 0L, 0L, List.of(), List.of(), 0L, 0L)).thenReturn(userResponseDTO);

        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);

        userService.updateUser(savedUser, patchUserDTO);

        verify(userRepository).saveAndFlush(userCaptor.capture());
        assertThat(userCaptor.getValue().getEmail()).isEqualTo("new.email@email.com");
    }

    @Test
    @DisplayName("[updateUser] Should Not Change Email - When The Same Value Is Provided")
    void shouldNotChangeEmailWhenSameValueProvided() {
        PatchUserDTO patchUserDTO = new PatchUserDTO(null, "  " + savedUser.getEmail().toUpperCase() + "  ", null, null, null, null, null);
        when(userRepository.saveAndFlush(any(User.class))).thenReturn(savedUser);
        when(userMapper.userToUserResponseDto(savedUser, 0L, 0L, 0L, List.of(), List.of(), 0L, 0L)).thenReturn(userResponseDTO);

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
        when(userMapper.userToUserResponseDto(savedUser, 0L, 0L, 0L, List.of(), List.of(), 0L, 0L)).thenReturn(userResponseDTO);

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
        when(userMapper.userToUserResponseDto(savedUser, 0L, 0L, 0L, List.of(), List.of(), 0L, 0L)).thenReturn(userResponseDTO);

        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);

        userService.updateUser(savedUser, patchUserDTO);

        verify(userRepository).saveAndFlush(userCaptor.capture());
        assertThat(userCaptor.getValue().getPassword()).isEqualTo("newHashedPassword");
    }

    @Test
    @DisplayName("[updateUser] Should Revoke All Refresh Tokens - When Password Changes")
    void shouldRevokeAllRefreshTokensWhenPasswordChanges() {
        UUID id = savedUser.getId();
        PatchUserDTO patchUserDTO = new PatchUserDTO(null, null, "NewPassword123", null, null, null, "Password123");
        when(passwordEncoder.matches("NewPassword123", savedUser.getPassword())).thenReturn(false);
        when(passwordEncoder.matches("Password123", savedUser.getPassword())).thenReturn(true);
        when(passwordEncoder.encode("NewPassword123")).thenReturn("newHashedPassword");
        when(userRepository.saveAndFlush(any(User.class))).thenReturn(savedUser);
        when(userMapper.userToUserResponseDto(savedUser, 0L, 0L, 0L, List.of(), List.of(), 0L, 0L)).thenReturn(userResponseDTO);

        userService.updateUser(savedUser, patchUserDTO);

        verify(refreshTokenService).invalidateAllSessions(id);
    }

    @Test
    @DisplayName("[updateUser] Should Revoke All Refresh Tokens - When Email Changes")
    void shouldRevokeAllRefreshTokensWhenEmailChanges() {
        UUID id = savedUser.getId();
        PatchUserDTO patchUserDTO = new PatchUserDTO(null, "new.email@email.com", null, null, null, null, "Password123");
        when(passwordEncoder.matches("Password123", savedUser.getPassword())).thenReturn(true);
        when(userRepository.saveAndFlush(any(User.class))).thenReturn(savedUser);
        when(userMapper.userToUserResponseDto(savedUser, 0L, 0L, 0L, List.of(), List.of(), 0L, 0L)).thenReturn(userResponseDTO);

        userService.updateUser(savedUser, patchUserDTO);

        verify(refreshTokenService).invalidateAllSessions(id);
    }

    @Test
    @DisplayName("[updateUser] Should Not Revoke Any Refresh Token - When Neither Password Nor Email Changes")
    void shouldNotRevokeAnyRefreshTokenWhenNeitherPasswordNorEmailChanges() {
        PatchUserDTO patchUserDTO = new PatchUserDTO(null, null, null, "New bio", null, null, null);
        when(userRepository.saveAndFlush(any(User.class))).thenReturn(savedUser);
        when(userMapper.userToUserResponseDto(savedUser, 0L, 0L, 0L, List.of(), List.of(), 0L, 0L)).thenReturn(userResponseDTO);

        userService.updateUser(savedUser, patchUserDTO);

        verifyNoInteractions(refreshTokenService);
    }

    @Test
    @DisplayName("[updateUser] Should Update Description - When A Different Value Is Provided")
    void shouldUpdateDescriptionWhenDifferentValueProvided() {
        PatchUserDTO patchUserDTO = new PatchUserDTO(null, null, null, "New description", null, null, null);
        when(userRepository.saveAndFlush(any(User.class))).thenReturn(savedUser);
        when(userMapper.userToUserResponseDto(savedUser, 0L, 0L, 0L, List.of(), List.of(), 0L, 0L)).thenReturn(userResponseDTO);

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
        when(userMapper.userToUserResponseDto(savedUser, 0L, 0L, 0L, List.of(), List.of(), 0L, 0L)).thenReturn(userResponseDTO);

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
        when(userMapper.userToUserResponseDto(savedUser, 0L, 0L, 0L, List.of(), List.of(), 0L, 0L)).thenReturn(userResponseDTO);

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
        when(userMapper.userToUserResponseDto(savedUser, 0L, 0L, 0L, List.of(), List.of(), 0L, 0L)).thenReturn(userResponseDTO);

        userService.updateUser(savedUser, patchUserDTO);

        assertThat(savedUser.getBanner()).isEqualTo("https://picture.com/banner.png");
    }

    @Test
    @DisplayName("[updateUser] Should Update PreferredLanguage - When A Different Value Is Provided")
    void shouldUpdatePreferredLanguageWhenDifferentValueProvided() {
        PatchUserDTO patchUserDTO = new PatchUserDTO(null, null, null, null, null, null, null, null, "pt-BR", null);
        when(userRepository.saveAndFlush(any(User.class))).thenReturn(savedUser);
        when(userMapper.userToUserResponseDto(savedUser, 0L, 0L, 0L, List.of(), List.of(), 0L, 0L)).thenReturn(userResponseDTO);

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
        when(userMapper.userToUserResponseDto(savedUser, 0L, 0L, 0L, List.of(), List.of(), 0L, 0L)).thenReturn(userResponseDTO);

        userService.updateUser(savedUser, patchUserDTO);

        assertThat(savedUser.getPreferredLanguage()).isEqualTo("en-US");
    }

    @Test
    @DisplayName("[updateUser] Should Update PreferredRegion - When A Different Value Is Provided")
    void shouldUpdatePreferredRegionWhenDifferentValueProvided() {
        PatchUserDTO patchUserDTO = new PatchUserDTO(null, null, null, null, null, null, null, null, null, "BR");
        when(userRepository.saveAndFlush(any(User.class))).thenReturn(savedUser);
        when(userMapper.userToUserResponseDto(savedUser, 0L, 0L, 0L, List.of(), List.of(), 0L, 0L)).thenReturn(userResponseDTO);

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
        when(userMapper.userToUserResponseDto(savedUser, 0L, 0L, 0L, List.of(), List.of(), 0L, 0L)).thenReturn(userResponseDTO);

        userService.updateUser(savedUser, patchUserDTO);

        assertThat(savedUser.getPreferredRegion()).isEqualTo("US");
    }

    @Test
    @DisplayName("[updateUser] Should Update IsProfilePublic - When A Different Value Is Provided")
    void shouldUpdateIsProfilePublicWhenDifferentValueProvided() {
        PatchUserDTO patchUserDTO = new PatchUserDTO(null, null, null, null, null, false, null);
        when(userRepository.saveAndFlush(any(User.class))).thenReturn(savedUser);
        when(userMapper.userToUserResponseDto(savedUser, 0L, 0L, 0L, List.of(), List.of(), 0L, 0L)).thenReturn(userResponseDTO);

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
        when(userMapper.userToUserResponseDto(savedUser, 0L, 0L, 0L, List.of(), List.of(), 0L, 0L)).thenReturn(userResponseDTO);

        userService.updateUser(savedUser, patchUserDTO);

        verify(followerService).acceptAllPendingFollowRequestsFor(id);
    }

    @Test
    @DisplayName("[updateUser] Should Not Accept Pending Follow Requests - When Profile Is Already Public")
    void shouldNotAcceptPendingFollowRequestsWhenProfileIsAlreadyPublic() {
        savedUser.setIsProfilePublic(true);
        PatchUserDTO patchUserDTO = new PatchUserDTO(null, null, null, null, null, true, null);
        when(userRepository.saveAndFlush(any(User.class))).thenReturn(savedUser);
        when(userMapper.userToUserResponseDto(savedUser, 0L, 0L, 0L, List.of(), List.of(), 0L, 0L)).thenReturn(userResponseDTO);

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

        verify(userMapper, never()).userToUserResponseDto(any(), anyLong(), anyLong(), anyLong(), any(), any(), anyLong(), anyLong());
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

        verify(userMapper, never()).userToUserResponseDto(any(), anyLong(), anyLong(), anyLong(), any(), any(), anyLong(), anyLong());
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
        when(userMapper.userToUserResponseDto(savedUser, 0L, 0L, 0L, List.of(), List.of(), 0L, 0L)).thenReturn(userResponseDTO);

        UserResponseDTO result = userService.login(loginUserDTO);

        assertThat(result).isEqualTo(userResponseDTO);
    }

    @Test
    @DisplayName("[login] Should Return UserResponseDTO - When Credentials Are Valid Using Username")
    void shouldReturnUserResponseDtoWhenCredentialsAreValidUsingUsername() {
        LoginUserDTO loginUserDTO = new LoginUserDTO(savedUser.getUsername(), "Password123");
        when(userRepository.findByUsernameIgnoreCase(savedUser.getUsername())).thenReturn(Optional.of(savedUser));
        when(passwordEncoder.matches("Password123", savedUser.getPassword())).thenReturn(true);
        when(userMapper.userToUserResponseDto(savedUser, 0L, 0L, 0L, List.of(), List.of(), 0L, 0L)).thenReturn(userResponseDTO);

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
        when(userMapper.userToUserResponseDto(savedUser, 0L, 0L, 0L, List.of(), List.of(), 0L, 0L)).thenReturn(userResponseDTO);

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
        when(userMapper.userToUserResponseDto(savedUser, 0L, 0L, 0L, List.of(), List.of(), 0L, 0L)).thenReturn(userResponseDTO);

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

        verifyNoInteractions(passwordEncoder, userMapper);
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
        when(userMapper.userToUserResponseDto(savedUser, 0L, 0L, 0L, List.of(), List.of(), 0L, 0L)).thenReturn(userResponseDTO);

        Optional<UserResponseDTO> result = userService.findByEmail(savedUser.getEmail());

        assertThat(result).contains(userResponseDTO);
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
        when(userMapper.userToUserResponseDto(savedUser, 0L, 0L, 0L, List.of(), List.of(), 0L, 0L)).thenReturn(userResponseDTO);

        userService.findByEmail("  " + savedUser.getEmail() + "  ");

        verify(userRepository).findByEmailIgnoreCase(savedUser.getEmail());
    }

    @Test
    @DisplayName("[deleteAccount] Should Delete User - When Password Matches")
    void shouldDeleteUserWhenPasswordMatches() {
        UUID id = savedUser.getId();
        DeleteAccountDTO deleteAccountDTO = new DeleteAccountDTO("Password123");
        when(userRepository.findById(id)).thenReturn(Optional.of(savedUser));
        when(passwordEncoder.matches("Password123", savedUser.getPassword())).thenReturn(true);

        userService.deleteAccount(id, deleteAccountDTO);

        verify(userRepository).delete(savedUser);
    }

    @Test
    @DisplayName("[deleteAccount] Should Throw UnauthorizedException - When Password Does Not Match")
    void shouldThrowUnauthorizedExceptionWhenDeleteAccountPasswordDoesNotMatch() {
        UUID id = savedUser.getId();
        DeleteAccountDTO deleteAccountDTO = new DeleteAccountDTO("WrongPassword123");
        when(userRepository.findById(id)).thenReturn(Optional.of(savedUser));
        when(passwordEncoder.matches("WrongPassword123", savedUser.getPassword())).thenReturn(false);

        assertThatThrownBy(() -> userService.deleteAccount(id, deleteAccountDTO))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessage("Invalid password");

        verify(userRepository, never()).delete(any());
    }

    @Test
    @DisplayName("[deleteAccount] Should Throw NotFoundException - When Id Does Not Exist")
    void shouldThrowNotFoundExceptionWhenDeletingAccountThatDoesNotExist() {
        UUID id = UUID.randomUUID();
        DeleteAccountDTO deleteAccountDTO = new DeleteAccountDTO("Password123");
        when(userRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userService.deleteAccount(id, deleteAccountDTO))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("User not found");

        verifyNoInteractions(passwordEncoder);
        verify(userRepository, never()).delete(any());
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