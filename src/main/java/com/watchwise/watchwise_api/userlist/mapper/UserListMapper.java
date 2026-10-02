package com.watchwise.watchwise_api.userlist.mapper;

import com.watchwise.watchwise_api.content.dto.ContentRefDTO;
import com.watchwise.watchwise_api.userlist.dto.UserListDetailedResponseDTO;
import com.watchwise.watchwise_api.userlist.dto.UserListItemResponseDTO;
import com.watchwise.watchwise_api.userlist.dto.UserListItemScope;
import com.watchwise.watchwise_api.userlist.dto.UserListResponseDTO;
import com.watchwise.watchwise_api.userlist.entity.UserList;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;

import java.util.List;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface UserListMapper {

    UserListResponseDTO userListToResponseDto(
            UserList userList, List<ContentRefDTO> previewItems, long nestedListsCount, double watchedPercentage, boolean likedByMe,
            long itemsCount, long commentsCount, long totalRuntimeMinutes, UserListItemScope itemScope, Boolean containsContent);

    @Mapping(target = "itemsPage", source = "itemsPage")
    @Mapping(target = "itemsSize", source = "itemsSize")
    @Mapping(target = "itemsTotalElements", source = "itemsTotalElements")
    @Mapping(target = "itemsTotalPages", source = "itemsTotalPages")
    @Mapping(target = "itemsHasNext", source = "itemsHasNext")
    UserListDetailedResponseDTO userListToDetailedResponseDto(
            UserList userList, List<UserListItemResponseDTO> items, double watchedPercentage, boolean likedByMe,
            long itemsCount, long commentsCount, long totalRuntimeMinutes, UserListItemScope itemScope,
            int itemsPage, int itemsSize, long itemsTotalElements, int itemsTotalPages, boolean itemsHasNext);

}
