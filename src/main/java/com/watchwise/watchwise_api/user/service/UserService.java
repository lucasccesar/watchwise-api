package com.watchwise.watchwise_api.user.service;

import com.watchwise.watchwise_api.user.dto.DeleteAccountDTO;
import com.watchwise.watchwise_api.user.dto.LoginUserDTO;
import com.watchwise.watchwise_api.user.dto.PatchUserDTO;
import com.watchwise.watchwise_api.user.dto.PostUserDTO;
import com.watchwise.watchwise_api.user.dto.PublicUserDTO;
import com.watchwise.watchwise_api.user.dto.UserPreviewDTO;
import com.watchwise.watchwise_api.user.dto.UserResponseDTO;
import com.watchwise.watchwise_api.user.entity.User;
import org.springframework.data.domain.Page;

import java.util.Optional;
import java.util.UUID;

public interface UserService {

    UserResponseDTO saveNewUser(PostUserDTO postUserDTO);

    PublicUserDTO getUserById(UUID id);

    UserResponseDTO getCurrentUser(UUID id);

    Page<UserPreviewDTO> getUsersByUsername(String username, Integer pageNumber, Integer pageSize);

    UserResponseDTO updateUser(User user, PatchUserDTO patchUserDTO);

    CredentialCheck checkCredentialChanges(UUID id, PatchUserDTO patchUserDTO);

    record CredentialCheck(User user, boolean touchesCredentials) {
    }

    UserResponseDTO login(LoginUserDTO loginUserDTO);

    Optional<UserResponseDTO> findByEmail(String email);

    void deleteAccount(UUID id, DeleteAccountDTO deleteAccountDTO);

}
