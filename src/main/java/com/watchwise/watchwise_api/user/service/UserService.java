package com.watchwise.watchwise_api.user.service;

import com.watchwise.watchwise_api.user.dto.PostUserDTO;
import com.watchwise.watchwise_api.user.dto.UserResponseDTO;

public interface UserService {

    public UserResponseDTO saveNewUser(PostUserDTO postUserDTO);

}
