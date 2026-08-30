package com.watchwise.watchwise_api.user.mapper;

import com.watchwise.watchwise_api.common.dto.GenreCountDTO;
import com.watchwise.watchwise_api.user.dto.PostUserDTO;
import com.watchwise.watchwise_api.user.dto.PublicUserDTO;
import com.watchwise.watchwise_api.user.dto.PublicUserProfileDTO;
import com.watchwise.watchwise_api.user.dto.UserPreviewDTO;
import com.watchwise.watchwise_api.user.dto.UserResponseDTO;
import com.watchwise.watchwise_api.user.entity.User;
import org.mapstruct.*;

import java.util.List;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface UserMapper {

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "password", ignore = true)
    @Mapping(target = "isEmailVerified", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    @Mapping(target = "sessionsInvalidatedAt", ignore = true)
    @Mapping(target = "preferredLanguage", ignore = true)
    @Mapping(target = "preferredRegion", ignore = true)
    User postUserDtoToUser(PostUserDTO postUserDTO);

    UserResponseDTO userToUserResponseDto(User user, long totalMinutesWatched, long minutesWatchedLast30Days,
            List<GenreCountDTO> genreCounts, long followersCount, long followingCount);

    UserPreviewDTO userToUserPreviewDto(User user);

    PublicUserDTO userToPublicUserDto(User user);

    PublicUserProfileDTO userToPublicUserProfileDto(User user, long totalMinutesWatched, long minutesWatchedLast30Days,
            List<GenreCountDTO> genreCounts, long followersCount, long followingCount);

    @AfterMapping
    default void applyDefaults(@MappingTarget User.UserBuilder builder, PostUserDTO dto) {
        if (dto.isProfilePublic() == null) {
            builder.isProfilePublic(true);
        }
        if(dto.profilePicture() == null){
            builder.profilePicture("https://default-image.png");
        }
    }


}
