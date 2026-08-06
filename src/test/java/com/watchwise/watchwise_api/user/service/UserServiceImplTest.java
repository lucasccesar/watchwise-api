package com.watchwise.watchwise_api.user.service.impl;

import com.watchwise.watchwise_api.common.exception.ConflictException;
import com.watchwise.watchwise_api.common.exception.NotFoundException;
import com.watchwise.watchwise_api.user.dto.PostUserDTO;
import com.watchwise.watchwise_api.user.dto.UserResponseDTO;
import com.watchwise.watchwise_api.user.entity.User;
import com.watchwise.watchwise_api.user.mapper.UserMapper;
import com.watchwise.watchwise_api.user.repository.UserRepository;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UserServiceImplTest {

    @Mock
    private UserMapper userMapper;

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private UserServiceImpl userService;

    private PostUserDTO postUserDTO;
    private User mappedUser;
    private User savedUser;
    private UserResponseDTO userResponseDTO;

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

        // Objeto retornado pelo mapper antes das normalizações feitas no service
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
                .build();

        userResponseDTO = new UserResponseDTO(
                savedUser.getId(),
                savedUser.getUsername(),
                savedUser.getEmail(),
                savedUser.getDescription(),
                savedUser.getProfilePicture(),
                savedUser.getIsProfilePublic(),
                savedUser.getCreatedAt()
        );
    }

    @Test
    void shouldReturnUserResponseDtoWhenSaveIsSuccessful() {
        when(userMapper.postUserDtoToUser(postUserDTO)).thenReturn(mappedUser);
        when(userRepository.save(any(User.class))).thenReturn(savedUser);
        when(userMapper.userToUserResponseDto(savedUser)).thenReturn(userResponseDTO);

        UserResponseDTO result = userService.saveNewUser(postUserDTO);

        assertThat(result).isEqualTo(userResponseDTO);
        verify(userRepository).save(any(User.class));
        verify(userMapper).userToUserResponseDto(savedUser);
    }

    @Test
    void shouldNormalizeEmailUsernameAndPasswordBeforeSavingWhenDataIsValid() {
        when(userMapper.postUserDtoToUser(postUserDTO)).thenReturn(mappedUser);
        when(userRepository.save(any(User.class))).thenReturn(savedUser);
        when(userMapper.userToUserResponseDto(savedUser)).thenReturn(userResponseDTO);

        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);

        userService.saveNewUser(postUserDTO);

        verify(userRepository).save(userCaptor.capture());
        User capturedUser = userCaptor.getValue();

        assertThat(capturedUser.getUsername()).isEqualTo("JohnDoe");
        assertThat(capturedUser.getEmail()).isEqualTo("john.doe@email.com");
        assertThat(capturedUser.getPassword()).isEqualTo("Password123");
    }

    @Test
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
    void shouldReturnUserResponseDtoWhenIdExists() {
        UUID id = savedUser.getId();
        when(userRepository.findById(id)).thenReturn(Optional.of(savedUser));
        when(userMapper.userToUserResponseDto(savedUser)).thenReturn(userResponseDTO);

        UserResponseDTO result = userService.getUserById(id);

        assertThat(result).isEqualTo(userResponseDTO);
        verify(userRepository).findById(id);
        verify(userMapper).userToUserResponseDto(savedUser);
    }

    @Test
    void shouldThrowNotFoundExceptionWhenIdDoesNotExist() {
        UUID id = UUID.randomUUID();
        when(userRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userService.getUserById(id))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("User not found");

        verify(userMapper, never()).userToUserResponseDto(any());
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