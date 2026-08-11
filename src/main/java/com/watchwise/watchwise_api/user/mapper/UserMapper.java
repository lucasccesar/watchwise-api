package com.watchwise.watchwise_api.user.mapper;

import com.watchwise.watchwise_api.user.dto.PostUserDTO;
import com.watchwise.watchwise_api.user.dto.PublicUserDTO;
import com.watchwise.watchwise_api.user.dto.UserPreviewDTO;
import com.watchwise.watchwise_api.user.dto.UserResponseDTO;
import com.watchwise.watchwise_api.user.entity.User;
import org.mapstruct.*;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface UserMapper {

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "password", ignore = true)
    @Mapping(target = "isEmailVerified", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    User postUserDtoToUser(PostUserDTO postUserDTO);

    UserResponseDTO userToUserResponseDto(User user);

    UserPreviewDTO userToUserPreviewDto(User user);

    PublicUserDTO userToPublicUserDto(User user);

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
