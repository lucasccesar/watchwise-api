package com.watchwise.watchwise_api.user.service.impl;

import com.watchwise.watchwise_api.common.exception.BadRequestException;
import com.watchwise.watchwise_api.common.exception.ConflictException;
import com.watchwise.watchwise_api.common.exception.ForbiddenException;
import com.watchwise.watchwise_api.common.exception.NotFoundException;
import com.watchwise.watchwise_api.common.exception.UnauthorizedException;
import com.watchwise.watchwise_api.user.dto.LoginUserDTO;
import com.watchwise.watchwise_api.user.dto.PatchUserDTO;
import com.watchwise.watchwise_api.user.dto.PostUserDTO;
import com.watchwise.watchwise_api.user.dto.PublicUserDTO;
import com.watchwise.watchwise_api.user.dto.UserPreviewDTO;
import com.watchwise.watchwise_api.user.dto.UserResponseDTO;
import com.watchwise.watchwise_api.user.entity.User;
import com.watchwise.watchwise_api.user.mapper.UserMapper;
import com.watchwise.watchwise_api.user.repository.UserRepository;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
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

    @InjectMocks
    private UserServiceImpl userService;

    @Captor
    private ArgumentCaptor<PageRequest> pageRequestCaptor;


    private PostUserDTO postUserDTO;
    private User mappedUser;
    private User savedUser;
    private UserResponseDTO userResponseDTO;
    private PublicUserDTO publicUserDTO;

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

        publicUserDTO = new PublicUserDTO(
                savedUser.getId(),
                savedUser.getUsername(),
                savedUser.getDescription(),
                savedUser.getProfilePicture(),
                savedUser.getIsProfilePublic(),
                savedUser.getCreatedAt()
        );
    }

    @Test
    @DisplayName("[saveNewUser] Should Return UserResponseDTO - When Save Is Successful")
    void shouldReturnUserResponseDtoWhenSaveIsSuccessful() {
        when(userMapper.postUserDtoToUser(postUserDTO)).thenReturn(mappedUser);
        when(passwordEncoder.encode(postUserDTO.password())).thenReturn("hashedPassword");
        when(userRepository.save(any(User.class))).thenReturn(savedUser);
        when(userMapper.userToUserResponseDto(savedUser)).thenReturn(userResponseDTO);

        UserResponseDTO result = userService.saveNewUser(postUserDTO);

        assertThat(result).isEqualTo(userResponseDTO);
        verify(userRepository).save(any(User.class));
        verify(userMapper).userToUserResponseDto(savedUser);
    }

    @Test
    @DisplayName("[saveNewUser] Should Normalize Email, Username And Password - When Data Is Valid")
    void shouldNormalizeEmailUsernameAndPasswordBeforeSavingWhenDataIsValid() {
        when(userMapper.postUserDtoToUser(postUserDTO)).thenReturn(mappedUser);
        when(passwordEncoder.encode(postUserDTO.password())).thenReturn("hashedPassword");
        when(userRepository.save(any(User.class))).thenReturn(savedUser);
        when(userMapper.userToUserResponseDto(savedUser)).thenReturn(userResponseDTO);

        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);

        userService.saveNewUser(postUserDTO);

        verify(userRepository).save(userCaptor.capture());
        User capturedUser = userCaptor.getValue();

        assertThat(capturedUser.getUsername()).isEqualTo("JohnDoe");
        assertThat(capturedUser.getEmail()).isEqualTo("john.doe@email.com");
        assertThat(capturedUser.getPassword()).isEqualTo("hashedPassword");
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

        verify(userMapper, never()).userToUserResponseDto(any());
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

        verify(userMapper, never()).userToUserResponseDto(any());
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

        verify(userMapper, never()).userToUserResponseDto(any());
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

        verify(userMapper, never()).userToUserResponseDto(any());
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

        verify(userMapper, never()).userToUserResponseDto(any());
    }

    @Test
    @DisplayName("[getUserById] Should Return PublicUserDTO - When Id Exists And Profile Is Public")
    void shouldReturnPublicUserDtoWhenIdExistsAndProfileIsPublic() {
        UUID id = savedUser.getId();
        when(userRepository.findById(id)).thenReturn(Optional.of(savedUser));
        when(userMapper.userToPublicUserDto(savedUser)).thenReturn(publicUserDTO);

        PublicUserDTO result = userService.getUserById(id);

        assertThat(result).isEqualTo(publicUserDTO);
        verify(userRepository).findById(id);
        verify(userMapper).userToPublicUserDto(savedUser);
    }

    @Test
    @DisplayName("[getUserById] Should Throw NotFoundException - When Id Does Not Exist")
    void shouldThrowNotFoundExceptionWhenIdDoesNotExist() {
        UUID id = UUID.randomUUID();
        when(userRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userService.getUserById(id))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("User not found");

        verify(userMapper, never()).userToPublicUserDto(any());
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

        verify(userMapper, never()).userToPublicUserDto(any());
    }

    @Test
    @DisplayName("[getCurrentUser] Should Return UserResponseDTO - When Id Exists")
    void shouldReturnUserResponseDtoWhenIdExists() {
        UUID id = savedUser.getId();
        when(userRepository.findById(id)).thenReturn(Optional.of(savedUser));
        when(userMapper.userToUserResponseDto(savedUser)).thenReturn(userResponseDTO);

        UserResponseDTO result = userService.getCurrentUser(id);

        assertThat(result).isEqualTo(userResponseDTO);
        verify(userRepository).findById(id);
        verify(userMapper).userToUserResponseDto(savedUser);
    }

    @Test
    @DisplayName("[getCurrentUser] Should Return UserResponseDTO - When Profile Is Private")
    void shouldReturnUserResponseDtoWhenProfileIsPrivate() {
        UUID id = savedUser.getId();
        savedUser.setIsProfilePublic(false);
        when(userRepository.findById(id)).thenReturn(Optional.of(savedUser));
        when(userMapper.userToUserResponseDto(savedUser)).thenReturn(userResponseDTO);

        UserResponseDTO result = userService.getCurrentUser(id);

        assertThat(result).isEqualTo(userResponseDTO);
    }

    @Test
    @DisplayName("[getCurrentUser] Should Throw NotFoundException - When Id Does Not Exist")
    void shouldThrowNotFoundExceptionWhenGettingCurrentUserThatDoesNotExist() {
        UUID id = UUID.randomUUID();
        when(userRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userService.getCurrentUser(id))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("User not found");

        verify(userMapper, never()).userToUserResponseDto(any());
    }

    @Test
    @DisplayName("[getUsersByUsername] Should Throw BadRequestException - When Username Is Null")
    void shouldThrowException_whenUsernameIsNull() {
        assertThrows(BadRequestException.class,
                () -> userService.getUsersByUsername(null, 1, 10, true));

        verifyNoInteractions(userRepository, userMapper);
    }

    @Test
    @DisplayName("[getUsersByUsername] Should Throw BadRequestException - When Username Is Empty")
    void shouldThrowException_whenUsernameIsEmpty() {
        assertThrows(BadRequestException.class,
                () -> userService.getUsersByUsername("", 1, 10, true));

        verifyNoInteractions(userRepository, userMapper);
    }

    @Test
    @DisplayName("[getUsersByUsername] Should Return Page Of DTOs - When Happy Path")
    void shouldReturnPageOfDtos_happyPath() {
        String username = "john";
        Page<User> userPage = new PageImpl<>(List.of(savedUser));
        UserPreviewDTO dto = new UserPreviewDTO(
                savedUser.getId(),
                savedUser.getUsername(),
                savedUser.getProfilePicture(),
                savedUser.getIsProfilePublic()
        );

        when(userRepository.findByUsernameStartingWithIgnoreCase(eq(username), anyBoolean(), any(PageRequest.class)))
                .thenReturn(userPage);
        when(userMapper.userToUserPreviewDto(savedUser)).thenReturn(dto);

        Page<UserPreviewDTO> result = userService.getUsersByUsername(username, 1, 10, true);

        assertEquals(1, result.getTotalElements());
        assertEquals(dto, result.getContent().get(0));
        verify(userMapper).userToUserPreviewDto(savedUser);
    }

    @Test
    @DisplayName("[getUsersByUsername] Should Return Empty Page - When No Users Are Found")
    void shouldReturnEmptyPage_whenNoUsersFound() {
        String username = "john";

        when(userRepository.findByUsernameStartingWithIgnoreCase(eq(username), anyBoolean(), any(PageRequest.class)))
                .thenReturn(Page.empty());

        Page<UserPreviewDTO> result = userService.getUsersByUsername(username, 1, 10, true);

        assertTrue(result.isEmpty());
        verify(userMapper, never()).userToUserPreviewDto(any());
    }

    @Test
    @DisplayName("[getUsersByUsername] Should Set OnlyPublic True - When IsProfilePublic Is True")
    void shouldSetOnlyPublicTrue_whenIsProfilePublicIsTrue() {
        String username = "john";
        when(userRepository.findByUsernameStartingWithIgnoreCase(eq(username), eq(true), any(PageRequest.class)))
                .thenReturn(Page.empty());

        userService.getUsersByUsername(username, 1, 10, true);

        verify(userRepository).findByUsernameStartingWithIgnoreCase(eq(username), eq(true), any(PageRequest.class));
    }

    @Test
    @DisplayName("[getUsersByUsername] Should Set OnlyPublic False - When IsProfilePublic Is False")
    void shouldSetOnlyPublicFalse_whenIsProfilePublicIsFalse() {
        String username = "john";
        when(userRepository.findByUsernameStartingWithIgnoreCase(eq(username), eq(false), any(PageRequest.class)))
                .thenReturn(Page.empty());

        userService.getUsersByUsername(username, 1, 10, false);

        verify(userRepository).findByUsernameStartingWithIgnoreCase(eq(username), eq(false), any(PageRequest.class));
    }

    @Test
    @DisplayName("[getUsersByUsername] Should Set OnlyPublic False - When IsProfilePublic Is Null")
    void shouldSetOnlyPublicFalse_whenIsProfilePublicIsNull() {
        String username = "john";
        when(userRepository.findByUsernameStartingWithIgnoreCase(eq(username), eq(false), any(PageRequest.class)))
                .thenReturn(Page.empty());

        userService.getUsersByUsername(username, 1, 10, null);

        verify(userRepository).findByUsernameStartingWithIgnoreCase(eq(username), eq(false), any(PageRequest.class));
    }

    private void stubRepositoryReturningEmptyPage() {
        when(userRepository.findByUsernameStartingWithIgnoreCase(anyString(), anyBoolean(), any(PageRequest.class)))
                .thenReturn(Page.empty());
    }

    @Test
    @DisplayName("[getUsersByUsername] Should Use Default Page - When Page Number Is Null")
    void shouldUseDefaultPage_whenPageNumberIsNull() {
        stubRepositoryReturningEmptyPage();

        userService.getUsersByUsername("john", null, 10, true);

        verify(userRepository).findByUsernameStartingWithIgnoreCase(anyString(), anyBoolean(), pageRequestCaptor.capture());
        assertEquals(UserServiceImpl.DEFAULT_PAGE, pageRequestCaptor.getValue().getPageNumber());
    }

    @Test
    @DisplayName("[getUsersByUsername] Should Use Default Page - When Page Number Is Zero")
    void shouldUseDefaultPage_whenPageNumberIsZero() {
        stubRepositoryReturningEmptyPage();

        userService.getUsersByUsername("john", 0, 10, true);

        verify(userRepository).findByUsernameStartingWithIgnoreCase(anyString(), anyBoolean(), pageRequestCaptor.capture());
        assertEquals(UserServiceImpl.DEFAULT_PAGE, pageRequestCaptor.getValue().getPageNumber());
    }

    @Test
    @DisplayName("[getUsersByUsername] Should Use Page Number Minus One - When Page Number Is Positive")
    void shouldUsePageNumberMinusOne_whenPageNumberIsPositive() {
        stubRepositoryReturningEmptyPage();

        userService.getUsersByUsername("john", 3, 10, true);

        verify(userRepository).findByUsernameStartingWithIgnoreCase(anyString(), anyBoolean(), pageRequestCaptor.capture());
        assertEquals(2, pageRequestCaptor.getValue().getPageNumber());
    }

    @Test
    @DisplayName("[getUsersByUsername] Should Throw BadRequestException - When Page Number Is Negative")
    void shouldThrowException_whenPageNumberIsNegative() {
        assertThrows(BadRequestException.class,
                () -> userService.getUsersByUsername("john", -1, 10, true));

        verifyNoInteractions(userRepository, userMapper);
    }

    @Test
    @DisplayName("[getUsersByUsername] Should Use Default Page Size - When Page Size Is Null")
    void shouldUseDefaultPageSize_whenPageSizeIsNull() {
        stubRepositoryReturningEmptyPage();

        userService.getUsersByUsername("john", 1, null, true);

        verify(userRepository).findByUsernameStartingWithIgnoreCase(anyString(), anyBoolean(), pageRequestCaptor.capture());
        assertEquals(UserServiceImpl.DEFAULT_PAGE_SIZE, pageRequestCaptor.getValue().getPageSize());
    }

    @Test
    @DisplayName("[getUsersByUsername] Should Use Default Page Size - When Page Size Exceeds Limit")
    void shouldUseDefaultPageSize_whenPageSizeExceedsLimit() {
        stubRepositoryReturningEmptyPage();

        userService.getUsersByUsername("john", 1, 1001, true);

        verify(userRepository).findByUsernameStartingWithIgnoreCase(anyString(), anyBoolean(), pageRequestCaptor.capture());
        assertEquals(UserServiceImpl.DEFAULT_PAGE_SIZE, pageRequestCaptor.getValue().getPageSize());
    }

    @Test
    @DisplayName("[getUsersByUsername] Should Use Provided Page Size - When Page Size Is Valid")
    void shouldUseProvidedPageSize_whenValid() {
        stubRepositoryReturningEmptyPage();

        userService.getUsersByUsername("john", 1, 25, true);

        verify(userRepository).findByUsernameStartingWithIgnoreCase(anyString(), anyBoolean(), pageRequestCaptor.capture());
        assertEquals(25, pageRequestCaptor.getValue().getPageSize());
    }

    @Test
    @DisplayName("[getUsersByUsername] Should Use Provided Page Size - When Page Size Is At Max Limit")
    void shouldUseProvidedPageSize_whenPageSizeIsAtMaxLimit() {
        stubRepositoryReturningEmptyPage();

        userService.getUsersByUsername("john", 1, 1000, true);

        verify(userRepository).findByUsernameStartingWithIgnoreCase(anyString(), anyBoolean(), pageRequestCaptor.capture());
        assertEquals(1000, pageRequestCaptor.getValue().getPageSize());
    }

    @Test
    @DisplayName("[getUsersByUsername] Should Throw BadRequestException - When Page Size Is Negative")
    void shouldThrowException_whenPageSizeIsNegative() {
        assertThrows(BadRequestException.class,
                () -> userService.getUsersByUsername("john", 1, -5, true));

        verifyNoInteractions(userRepository, userMapper);
    }

    @Test
    @DisplayName("[getUsersByUsername] Should Throw BadRequestException - When Page Size Is Zero")
    void shouldThrowException_whenPageSizeIsZero() {
        assertThrows(BadRequestException.class,
                () -> userService.getUsersByUsername("john", 1, 0, true));

        verifyNoInteractions(userRepository, userMapper);
    }

    @Test
    @DisplayName("[getUsersByUsername] Should Not Apply Sort - When SortBy Is Always Null")
    void shouldNotApplySort_becauseSortByIsAlwaysNull() {
        stubRepositoryReturningEmptyPage();

        userService.getUsersByUsername("john", 1, 10, true);

        verify(userRepository).findByUsernameStartingWithIgnoreCase(anyString(), anyBoolean(), pageRequestCaptor.capture());
        assertTrue(pageRequestCaptor.getValue().getSort().isUnsorted());
    }

    @Test
    @DisplayName("[updateUser] Should Throw NotFoundException - When Updating A User That Does Not Exist")
    void shouldThrowNotFoundExceptionWhenUpdatingUserThatDoesNotExist() {
        UUID id = UUID.randomUUID();
        PatchUserDTO patchUserDTO = new PatchUserDTO(null, null, null, null, null, null);
        when(userRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userService.updateUser(id, patchUserDTO))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("User not found");

        verify(userRepository, never()).save(any());
    }

    @Test
    @DisplayName("[updateUser] Should Not Change Any Field - When All Patch Fields Are Null")
    void shouldNotChangeAnyFieldWhenAllPatchFieldsAreNull() {
        UUID id = savedUser.getId();
        PatchUserDTO patchUserDTO = new PatchUserDTO(null, null, null, null, null, null);
        when(userRepository.findById(id)).thenReturn(Optional.of(savedUser));
        when(userRepository.save(any(User.class))).thenReturn(savedUser);
        when(userMapper.userToUserResponseDto(savedUser)).thenReturn(userResponseDTO);

        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);

        userService.updateUser(id, patchUserDTO);

        verify(userRepository).save(userCaptor.capture());
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
        UUID id = savedUser.getId();
        PatchUserDTO patchUserDTO = new PatchUserDTO("  NewUsername  ", null, null, null, null, null);
        when(userRepository.findById(id)).thenReturn(Optional.of(savedUser));
        when(userRepository.save(any(User.class))).thenReturn(savedUser);
        when(userMapper.userToUserResponseDto(savedUser)).thenReturn(userResponseDTO);

        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);

        userService.updateUser(id, patchUserDTO);

        verify(userRepository).save(userCaptor.capture());
        assertThat(userCaptor.getValue().getUsername()).isEqualTo("NewUsername");
    }

    @Test
    @DisplayName("[updateUser] Should Not Change Username - When The Same Value Is Provided")
    void shouldNotChangeUsernameWhenSameValueProvided() {
        UUID id = savedUser.getId();
        PatchUserDTO patchUserDTO = new PatchUserDTO("  " + savedUser.getUsername() + "  ", null, null, null, null, null);
        when(userRepository.findById(id)).thenReturn(Optional.of(savedUser));
        when(userRepository.save(any(User.class))).thenReturn(savedUser);
        when(userMapper.userToUserResponseDto(savedUser)).thenReturn(userResponseDTO);

        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);

        userService.updateUser(id, patchUserDTO);

        verify(userRepository).save(userCaptor.capture());
        assertThat(userCaptor.getValue().getUsername()).isEqualTo(savedUser.getUsername());
    }

    @Test
    @DisplayName("[updateUser] Should Update Email Normalized - When A Different Value Is Provided")
    void shouldUpdateEmailNormalizedWhenDifferentValueProvided() {
        UUID id = savedUser.getId();
        PatchUserDTO patchUserDTO = new PatchUserDTO(null, "  NEW.EMAIL@EMAIL.COM  ", null, null, null, null);
        when(userRepository.findById(id)).thenReturn(Optional.of(savedUser));
        when(userRepository.save(any(User.class))).thenReturn(savedUser);
        when(userMapper.userToUserResponseDto(savedUser)).thenReturn(userResponseDTO);

        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);

        userService.updateUser(id, patchUserDTO);

        verify(userRepository).save(userCaptor.capture());
        assertThat(userCaptor.getValue().getEmail()).isEqualTo("new.email@email.com");
    }

    @Test
    @DisplayName("[updateUser] Should Not Change Email - When The Same Value Is Provided")
    void shouldNotChangeEmailWhenSameValueProvided() {
        UUID id = savedUser.getId();
        PatchUserDTO patchUserDTO = new PatchUserDTO(null, "  " + savedUser.getEmail().toUpperCase() + "  ", null, null, null, null);
        when(userRepository.findById(id)).thenReturn(Optional.of(savedUser));
        when(userRepository.save(any(User.class))).thenReturn(savedUser);
        when(userMapper.userToUserResponseDto(savedUser)).thenReturn(userResponseDTO);

        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);

        userService.updateUser(id, patchUserDTO);

        verify(userRepository).save(userCaptor.capture());
        assertThat(userCaptor.getValue().getEmail()).isEqualTo(savedUser.getEmail());
    }

    @Test
    @DisplayName("[updateUser] Should Not Re-Encode Password - When Provided Password Matches Current Hash")
    void shouldNotReEncodePasswordWhenProvidedPasswordMatchesCurrentHash() {
        UUID id = savedUser.getId();
        PatchUserDTO patchUserDTO = new PatchUserDTO(null, null, "SamePassword123", null, null, null);
        when(userRepository.findById(id)).thenReturn(Optional.of(savedUser));
        when(passwordEncoder.matches("SamePassword123", savedUser.getPassword())).thenReturn(true);
        when(userRepository.save(any(User.class))).thenReturn(savedUser);
        when(userMapper.userToUserResponseDto(savedUser)).thenReturn(userResponseDTO);

        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);

        userService.updateUser(id, patchUserDTO);

        verify(passwordEncoder, never()).encode(any());
        verify(userRepository).save(userCaptor.capture());
        assertThat(userCaptor.getValue().getPassword()).isEqualTo(savedUser.getPassword());
    }

    @Test
    @DisplayName("[updateUser] Should Encode And Update Password - When Provided Password Differs From Current Hash")
    void shouldEncodeAndUpdatePasswordWhenProvidedPasswordDiffersFromCurrentHash() {
        UUID id = savedUser.getId();
        PatchUserDTO patchUserDTO = new PatchUserDTO(null, null, "NewPassword123", null, null, null);
        when(userRepository.findById(id)).thenReturn(Optional.of(savedUser));
        when(passwordEncoder.matches("NewPassword123", savedUser.getPassword())).thenReturn(false);
        when(passwordEncoder.encode("NewPassword123")).thenReturn("newHashedPassword");
        when(userRepository.save(any(User.class))).thenReturn(savedUser);
        when(userMapper.userToUserResponseDto(savedUser)).thenReturn(userResponseDTO);

        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);

        userService.updateUser(id, patchUserDTO);

        verify(userRepository).save(userCaptor.capture());
        assertThat(userCaptor.getValue().getPassword()).isEqualTo("newHashedPassword");
    }

    @Test
    @DisplayName("[updateUser] Should Update Description - When A Different Value Is Provided")
    void shouldUpdateDescriptionWhenDifferentValueProvided() {
        UUID id = savedUser.getId();
        PatchUserDTO patchUserDTO = new PatchUserDTO(null, null, null, "New description", null, null);
        when(userRepository.findById(id)).thenReturn(Optional.of(savedUser));
        when(userRepository.save(any(User.class))).thenReturn(savedUser);
        when(userMapper.userToUserResponseDto(savedUser)).thenReturn(userResponseDTO);

        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);

        userService.updateUser(id, patchUserDTO);

        verify(userRepository).save(userCaptor.capture());
        assertThat(userCaptor.getValue().getDescription()).isEqualTo("New description");
    }

    @Test
    @DisplayName("[updateUser] Should Update ProfilePicture - When A Different Value Is Provided")
    void shouldUpdateProfilePictureWhenDifferentValueProvided() {
        UUID id = savedUser.getId();
        PatchUserDTO patchUserDTO = new PatchUserDTO(null, null, null, null, "https://new-picture.com/pic.png", null);
        when(userRepository.findById(id)).thenReturn(Optional.of(savedUser));
        when(userRepository.save(any(User.class))).thenReturn(savedUser);
        when(userMapper.userToUserResponseDto(savedUser)).thenReturn(userResponseDTO);

        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);

        userService.updateUser(id, patchUserDTO);

        verify(userRepository).save(userCaptor.capture());
        assertThat(userCaptor.getValue().getProfilePicture()).isEqualTo("https://new-picture.com/pic.png");
    }

    @Test
    @DisplayName("[updateUser] Should Update IsProfilePublic - When A Different Value Is Provided")
    void shouldUpdateIsProfilePublicWhenDifferentValueProvided() {
        UUID id = savedUser.getId();
        PatchUserDTO patchUserDTO = new PatchUserDTO(null, null, null, null, null, false);
        when(userRepository.findById(id)).thenReturn(Optional.of(savedUser));
        when(userRepository.save(any(User.class))).thenReturn(savedUser);
        when(userMapper.userToUserResponseDto(savedUser)).thenReturn(userResponseDTO);

        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);

        userService.updateUser(id, patchUserDTO);

        verify(userRepository).save(userCaptor.capture());
        assertThat(userCaptor.getValue().getIsProfilePublic()).isFalse();
    }

    @Test
    @DisplayName("[updateUser] Should Throw ConflictException With Username Message - When Update Violates Username Constraint")
    void shouldThrowConflictExceptionWithUsernameMessageWhenUpdateViolatesUsernameConstraint() {
        UUID id = savedUser.getId();
        PatchUserDTO patchUserDTO = new PatchUserDTO("TakenUsername", null, null, null, null, null);
        when(userRepository.findById(id)).thenReturn(Optional.of(savedUser));
        when(userRepository.save(any(User.class)))
                .thenThrow(buildDataIntegrityViolationException("uq_users_username"));

        assertThatThrownBy(() -> userService.updateUser(id, patchUserDTO))
                .isInstanceOf(ConflictException.class)
                .hasMessage("Username already in use");

        verify(userMapper, never()).userToUserResponseDto(any());
    }

    @Test
    @DisplayName("[login] Should Return UserResponseDTO - When Credentials Are Valid Using Email")
    void shouldReturnUserResponseDtoWhenCredentialsAreValidUsingEmail() {
        LoginUserDTO loginUserDTO = new LoginUserDTO(savedUser.getEmail(), "Password123");
        when(userRepository.findByUsernameIgnoreCaseOrEmailIgnoreCase(savedUser.getEmail(), savedUser.getEmail()))
                .thenReturn(Optional.of(savedUser));
        when(passwordEncoder.matches("Password123", savedUser.getPassword())).thenReturn(true);
        when(userMapper.userToUserResponseDto(savedUser)).thenReturn(userResponseDTO);

        UserResponseDTO result = userService.login(loginUserDTO);

        assertThat(result).isEqualTo(userResponseDTO);
    }

    @Test
    @DisplayName("[login] Should Return UserResponseDTO - When Credentials Are Valid Using Username")
    void shouldReturnUserResponseDtoWhenCredentialsAreValidUsingUsername() {
        LoginUserDTO loginUserDTO = new LoginUserDTO(savedUser.getUsername(), "Password123");
        when(userRepository.findByUsernameIgnoreCaseOrEmailIgnoreCase(savedUser.getUsername(), savedUser.getUsername()))
                .thenReturn(Optional.of(savedUser));
        when(passwordEncoder.matches("Password123", savedUser.getPassword())).thenReturn(true);
        when(userMapper.userToUserResponseDto(savedUser)).thenReturn(userResponseDTO);

        UserResponseDTO result = userService.login(loginUserDTO);

        assertThat(result).isEqualTo(userResponseDTO);
    }

    @Test
    @DisplayName("[login] Should Throw UnauthorizedException - When Identifier Does Not Match Any User")
    void shouldThrowUnauthorizedExceptionWhenIdentifierDoesNotMatchAnyUser() {
        LoginUserDTO loginUserDTO = new LoginUserDTO("unknown@email.com", "Password123");
        when(userRepository.findByUsernameIgnoreCaseOrEmailIgnoreCase("unknown@email.com", "unknown@email.com"))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> userService.login(loginUserDTO))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessage("Invalid credentials");

        verifyNoInteractions(passwordEncoder, userMapper);
    }

    @Test
    @DisplayName("[login] Should Throw UnauthorizedException - When Password Does Not Match")
    void shouldThrowUnauthorizedExceptionWhenPasswordDoesNotMatch() {
        LoginUserDTO loginUserDTO = new LoginUserDTO(savedUser.getEmail(), "WrongPassword123");
        when(userRepository.findByUsernameIgnoreCaseOrEmailIgnoreCase(savedUser.getEmail(), savedUser.getEmail()))
                .thenReturn(Optional.of(savedUser));
        when(passwordEncoder.matches("WrongPassword123", savedUser.getPassword())).thenReturn(false);

        assertThatThrownBy(() -> userService.login(loginUserDTO))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessage("Invalid credentials");

        verifyNoInteractions(userMapper);
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