package com.watchwise.watchwise_api.userlist.mapper;

import com.watchwise.watchwise_api.content.dto.ContentRefDTO;
import com.watchwise.watchwise_api.userlist.dto.UserListDetailedResponseDTO;
import com.watchwise.watchwise_api.userlist.dto.UserListItemResponseDTO;
import com.watchwise.watchwise_api.userlist.dto.UserListResponseDTO;
import com.watchwise.watchwise_api.userlist.entity.UserList;
import org.mapstruct.Mapper;
import org.mapstruct.ReportingPolicy;

import java.util.List;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface UserListMapper {

    UserListResponseDTO userListToResponseDto(
            UserList userList, List<ContentRefDTO> previewItems, long nestedListsCount, double watchedPercentage, boolean likedByMe);

    UserListDetailedResponseDTO userListToDetailedResponseDto(
            UserList userList, List<UserListItemResponseDTO> items, double watchedPercentage, boolean likedByMe);

}