package com.watchwise.watchwise_api.user.mapper;

import com.watchwise.watchwise_api.common.dto.GenreWatchTimeDTO;
import com.watchwise.watchwise_api.user.dto.PostUserDTO;
import com.watchwise.watchwise_api.user.dto.PublicUserProfileDTO;
import com.watchwise.watchwise_api.user.dto.UserResponseDTO;
import com.watchwise.watchwise_api.user.entity.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class UserMapperTest {

    private UserMapper userMapper;

    @BeforeEach
    void setUp() {
        userMapper = Mappers.getMapper(UserMapper.class);
    }

    @Test
    @DisplayName("[postUserDtoToUser] Should Map All Fields - When PostUserDTO Has All Values Filled")
    void shouldMapAllFieldsWhenPostUserDtoHasAllValuesFilled() {
        PostUserDTO dto = new PostUserDTO(
                "JohnDoe",
                "john.doe@email.com",
                "Password123",
                "Some description",
                "https://picture.com/pic.png",
                false
        );

        User result = userMapper.postUserDtoToUser(dto);

        assertThat(result.getUsername()).isEqualTo("JohnDoe");
        assertThat(result.getEmail()).isEqualTo("john.doe@email.com");
        assertThat(result.getDescription()).isEqualTo("Some description");
        assertThat(result.getProfilePicture()).isEqualTo("https://picture.com/pic.png");
        assertThat(result.getIsProfilePublic()).isFalse();
    }

    @Test
    @DisplayName("[postUserDtoToUser] Should Ignore Id, Password, CreatedAt And UpdatedAt - When Mapping PostUserDTO To User")
    void shouldIgnoreIdPasswordCreatedAtAndUpdatedAtWhenMappingPostUserDtoToUser() {
        PostUserDTO dto = new PostUserDTO(
                "JohnDoe",
                "john.doe@email.com",
                "Password123",
                "Some description",
                "https://picture.com/pic.png",
                true
        );

        User result = userMapper.postUserDtoToUser(dto);

        assertThat(result.getId()).isNull();
        assertThat(result.getPassword()).isNull();
        assertThat(result.getCreatedAt()).isNull();
        assertThat(result.getUpdatedAt()).isNull();
    }

    @Test
    @DisplayName("[postUserDtoToUser] Should Apply Entity Builder Default For IsEmailVerified - When Mapping PostUserDTO To User")
    void shouldApplyEntityBuilderDefaultForIsEmailVerifiedWhenMappingPostUserDtoToUser() {
        PostUserDTO dto = new PostUserDTO(
                "JohnDoe",
                "john.doe@email.com",
                "Password123",
                "Some description",
                "https://picture.com/pic.png",
                true
        );

        User result = userMapper.postUserDtoToUser(dto);

        assertThat(result.getIsEmailVerified()).isTrue();
    }

    @Test
    @DisplayName("[postUserDtoToUser] Should Apply Default IsProfilePublic - When Value Is Null")
    void shouldApplyDefaultIsProfilePublicWhenValueIsNull() {
        PostUserDTO dto = new PostUserDTO(
                "JohnDoe",
                "john.doe@email.com",
                "Password123",
                "Some description",
                "https://picture.com/pic.png",
                null
        );

        User result = userMapper.postUserDtoToUser(dto);

        assertThat(result.getIsProfilePublic()).isTrue();
    }

    @Test
    @DisplayName("[postUserDtoToUser] Should Not Override IsProfilePublic - When Value Is Provided")
    void shouldNotOverrideIsProfilePublicWhenValueIsProvided() {
        PostUserDTO dto = new PostUserDTO(
                "JohnDoe",
                "john.doe@email.com",
                "Password123",
                "Some description",
                "https://picture.com/pic.png",
                false
        );

        User result = userMapper.postUserDtoToUser(dto);

        assertThat(result.getIsProfilePublic()).isFalse();
    }

    @Test
    @DisplayName("[postUserDtoToUser] Should Apply Default ProfilePicture - When Value Is Null")
    void shouldApplyDefaultProfilePictureWhenValueIsNull() {
        PostUserDTO dto = new PostUserDTO(
                "JohnDoe",
                "john.doe@email.com",
                "Password123",
                "Some description",
                null,
                true
        );

        User result = userMapper.postUserDtoToUser(dto);

        assertThat(result.getProfilePicture()).isEqualTo("https://default-image.png");
    }

    @Test
    @DisplayName("[postUserDtoToUser] Should Not Override ProfilePicture - When Value Is Provided")
    void shouldNotOverrideProfilePictureWhenValueIsProvided() {
        PostUserDTO dto = new PostUserDTO(
                "JohnDoe",
                "john.doe@email.com",
                "Password123",
                "Some description",
                "https://picture.com/pic.png",
                true
        );

        User result = userMapper.postUserDtoToUser(dto);

        assertThat(result.getProfilePicture()).isEqualTo("https://picture.com/pic.png");
    }

    @Test
    @DisplayName("[userToUserResponseDto] Should Map All Fields - When Mapping User To UserResponseDTO")
    void shouldMapAllFieldsWhenMappingUserToUserResponseDto() {
        UUID id = UUID.randomUUID();
        LocalDateTime createdAt = LocalDateTime.now().minusDays(1);
        LocalDateTime updatedAt = LocalDateTime.now();

        User user = User.builder()
                .id(id)
                .username("JohnDoe")
                .email("john.doe@email.com")
                .password("Password123")
                .description("Some description")
                .profilePicture("https://picture.com/pic.png")
                .isProfilePublic(true)
                .createdAt(createdAt)
                .updatedAt(updatedAt)
                .build();

        List<GenreWatchTimeDTO> genres = List.of(new GenreWatchTimeDTO("Action", 120L));

        UserResponseDTO result = userMapper.userToUserResponseDto(user, 500L, 90L, genres, genres);

        assertThat(result.id()).isEqualTo(id);
        assertThat(result.username()).isEqualTo("JohnDoe");
        assertThat(result.email()).isEqualTo("john.doe@email.com");
        assertThat(result.description()).isEqualTo("Some description");
        assertThat(result.profilePicture()).isEqualTo("https://picture.com/pic.png");
        assertThat(result.isProfilePublic()).isTrue();
        assertThat(result.createdAt()).isEqualTo(createdAt);
        assertThat(result.updatedAt()).isEqualTo(updatedAt);
        assertThat(result.totalMinutesWatched()).isEqualTo(500L);
        assertThat(result.minutesWatchedLast30Days()).isEqualTo(90L);
        assertThat(result.genreMinutesWatched()).isEqualTo(genres);
        assertThat(result.genreMinutesWatchedLast30Days()).isEqualTo(genres);
    }

    @Test
    @DisplayName("[userToPublicUserProfileDto] Should Map All Fields - When Mapping User To PublicUserProfileDTO")
    void shouldMapAllFieldsWhenMappingUserToPublicUserProfileDto() {
        UUID id = UUID.randomUUID();
        LocalDateTime createdAt = LocalDateTime.now().minusDays(1);
        List<GenreWatchTimeDTO> genres = List.of(new GenreWatchTimeDTO("Drama", 60L));

        User user = User.builder()
                .id(id)
                .username("JaneDoe")
                .description("Some description")
                .profilePicture("https://picture.com/pic.png")
                .isProfilePublic(true)
                .createdAt(createdAt)
                .build();

        PublicUserProfileDTO result = userMapper.userToPublicUserProfileDto(user, 300L, 45L, genres, List.of());

        assertThat(result.id()).isEqualTo(id);
        assertThat(result.username()).isEqualTo("JaneDoe");
        assertThat(result.description()).isEqualTo("Some description");
        assertThat(result.profilePicture()).isEqualTo("https://picture.com/pic.png");
        assertThat(result.isProfilePublic()).isTrue();
        assertThat(result.createdAt()).isEqualTo(createdAt);
        assertThat(result.totalMinutesWatched()).isEqualTo(300L);
        assertThat(result.minutesWatchedLast30Days()).isEqualTo(45L);
        assertThat(result.genreMinutesWatched()).isEqualTo(genres);
        assertThat(result.genreMinutesWatchedLast30Days()).isEmpty();
    }
}