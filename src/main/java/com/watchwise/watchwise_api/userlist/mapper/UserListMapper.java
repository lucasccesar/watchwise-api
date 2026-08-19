package com.watchwise.watchwise_api.userlist.mapper;

import com.watchwise.watchwise_api.user.mapper.UserMapper;
import com.watchwise.watchwise_api.userlist.dto.UserListResponseDTO;
import com.watchwise.watchwise_api.userlist.entity.UserList;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR, uses = UserMapper.class)
public interface UserListMapper {

    // watchedPercentage is computed from UserListItem completion once that entity exists; hardcoded until then.
    @Mapping(target = "watchedPercentage", constant = "0.0")
    UserListResponseDTO userListToResponseDto(UserList userList);

}