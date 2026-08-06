package com.watchwise.watchwise_api.user.service;

import com.watchwise.watchwise_api.user.dto.PostUserDTO;
import com.watchwise.watchwise_api.user.dto.UserPreviewDto;
import com.watchwise.watchwise_api.user.dto.UserResponseDTO;
import org.springframework.data.domain.Page;

import java.util.UUID;

public interface UserService {

    UserResponseDTO saveNewUser(PostUserDTO postUserDTO);

    UserResponseDTO getUserById(UUID id);

    Page<UserPreviewDto> getUsersByUsername(String username, Integer pageNumber, Integer pageSize, Boolean isProfilePublic);

}
