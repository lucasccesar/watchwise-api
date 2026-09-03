package com.watchwise.watchwise_api.user.mapper;

import com.watchwise.watchwise_api.common.dto.GenreCountDTO;
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
                .banner("https://picture.com/banner.png")
                .isProfilePublic(true)
                .createdAt(createdAt)
                .updatedAt(updatedAt)
                .build();

        List<GenreCountDTO> genresMovies = List.of(new GenreCountDTO("Action", 5L));
        List<GenreCountDTO> genresEpisodes = List.of(new GenreCountDTO("Drama", 2L));

        UserResponseDTO result = userMapper.userToUserResponseDto(user, 500L, 90L, 8L, genresMovies, genresEpisodes, 12L, 7L);

        assertThat(result.id()).isEqualTo(id);
        assertThat(result.username()).isEqualTo("JohnDoe");
        assertThat(result.email()).isEqualTo("john.doe@email.com");
        assertThat(result.description()).isEqualTo("Some description");
        assertThat(result.profilePicture()).isEqualTo("https://picture.com/pic.png");
        assertThat(result.banner()).isEqualTo("https://picture.com/banner.png");
        assertThat(result.isProfilePublic()).isTrue();
        assertThat(result.createdAt()).isEqualTo(createdAt);
        assertThat(result.updatedAt()).isEqualTo(updatedAt);
        assertThat(result.totalMinutesWatched()).isEqualTo(500L);
        assertThat(result.minutesWatchedLast30Days()).isEqualTo(90L);
        assertThat(result.totalTheaterVisits()).isEqualTo(8L);
        assertThat(result.genreCountsMovies()).isEqualTo(genresMovies);
        assertThat(result.genreCountsEpisodes()).isEqualTo(genresEpisodes);
        assertThat(result.followersCount()).isEqualTo(12L);
        assertThat(result.followingCount()).isEqualTo(7L);
    }

    @Test
    @DisplayName("[userToPublicUserProfileDto] Should Map All Fields - When Mapping User To PublicUserProfileDTO")
    void shouldMapAllFieldsWhenMappingUserToPublicUserProfileDto() {
        UUID id = UUID.randomUUID();
        LocalDateTime createdAt = LocalDateTime.now().minusDays(1);
        List<GenreCountDTO> genresMovies = List.of(new GenreCountDTO("Drama", 3L));
        List<GenreCountDTO> genresEpisodes = List.of(new GenreCountDTO("Comedy", 1L));

        User user = User.builder()
                .id(id)
                .username("JaneDoe")
                .description("Some description")
                .profilePicture("https://picture.com/pic.png")
                .banner("https://picture.com/banner.png")
                .isProfilePublic(true)
                .createdAt(createdAt)
                .build();

        PublicUserProfileDTO result = userMapper.userToPublicUserProfileDto(user, 300L, 45L, 6L, genresMovies, genresEpisodes, 20L, 15L);

        assertThat(result.id()).isEqualTo(id);
        assertThat(result.username()).isEqualTo("JaneDoe");
        assertThat(result.description()).isEqualTo("Some description");
        assertThat(result.profilePicture()).isEqualTo("https://picture.com/pic.png");
        assertThat(result.banner()).isEqualTo("https://picture.com/banner.png");
        assertThat(result.isProfilePublic()).isTrue();
        assertThat(result.createdAt()).isEqualTo(createdAt);
        assertThat(result.totalMinutesWatched()).isEqualTo(300L);
        assertThat(result.minutesWatchedLast30Days()).isEqualTo(45L);
        assertThat(result.totalTheaterVisits()).isEqualTo(6L);
        assertThat(result.genreCountsMovies()).isEqualTo(genresMovies);
        assertThat(result.genreCountsEpisodes()).isEqualTo(genresEpisodes);
        assertThat(result.followersCount()).isEqualTo(20L);
        assertThat(result.followingCount()).isEqualTo(15L);
    }
}