package com.watchwise.watchwise_api.userlist.mapper;

import com.watchwise.watchwise_api.content.mapper.ContentMapper;
import com.watchwise.watchwise_api.user.mapper.UserMapper;
import com.watchwise.watchwise_api.userlist.dto.UserListItemResponseDTO;
import com.watchwise.watchwise_api.userlist.dto.UserListPreviewDTO;
import com.watchwise.watchwise_api.userlist.entity.UserList;
import com.watchwise.watchwise_api.userlist.entity.UserListItem;
import org.mapstruct.Mapper;
import org.mapstruct.ReportingPolicy;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR, uses = {ContentMapper.class, UserMapper.class})
public interface UserListItemMapper {

    UserListItemResponseDTO userListItemToResponseDto(UserListItem userListItem);

    UserListPreviewDTO userListToPreviewDto(UserList userList);

}