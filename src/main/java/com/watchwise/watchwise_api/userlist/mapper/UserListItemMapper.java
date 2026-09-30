package com.watchwise.watchwise_api.userlist.mapper;

import com.watchwise.watchwise_api.content.mapper.ContentMapper;
import com.watchwise.watchwise_api.user.mapper.UserMapper;
import com.watchwise.watchwise_api.userlist.dto.UserListItemResponseDTO;
import com.watchwise.watchwise_api.userlist.dto.UserListPreviewDTO;
import com.watchwise.watchwise_api.userlist.entity.UserList;
import com.watchwise.watchwise_api.userlist.entity.UserListItem;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR, uses = {ContentMapper.class, UserMapper.class})
public interface UserListItemMapper {

    @Mapping(target = "contentState", ignore = true)
    @Mapping(target = "customPosterUrl", ignore = true)
    @Mapping(target = "withCustomPosterUrl", ignore = true)
    @Mapping(target = "episodeAverageRating", ignore = true)
    @Mapping(target = "globalEpisodeAverageRating", ignore = true)
    @Mapping(target = "contentAverageRating", ignore = true)
    UserListItemResponseDTO userListItemToResponseDto(UserListItem userListItem);

    UserListPreviewDTO userListToPreviewDto(UserList userList);

}
