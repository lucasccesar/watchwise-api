package com.watchwise.watchwise_api.user.service.impl;

import com.watchwise.watchwise_api.common.exception.ConflictException;
import com.watchwise.watchwise_api.user.dto.PostUserDTO;
import com.watchwise.watchwise_api.user.dto.UserResponseDTO;
import com.watchwise.watchwise_api.user.entity.User;
import com.watchwise.watchwise_api.user.mapper.UserMapper;
import com.watchwise.watchwise_api.user.repository.UserRepository;
import com.watchwise.watchwise_api.user.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {

    private final UserMapper userMapper;
    private final UserRepository userRepository;

    @Override
    public UserResponseDTO saveNewUser(PostUserDTO postUserDTO) {

        User mapperUser = userMapper.postUserDtoToUser(postUserDTO);
        mapperUser.setPassword(postUserDTO.password());
        mapperUser.setEmail(postUserDTO.email().toLowerCase().trim());
        mapperUser.setUsername(postUserDTO.username().trim());

        try {
            return userMapper.userToUserResponseDto(userRepository.save(mapperUser));
        } catch (DataIntegrityViolationException e) {
            String constraintName = extractConstraintName(e);

            if ("uq_users_username".equals(constraintName)) {
                throw new ConflictException("Username already in use");
            }
            if ("uq_users_email".equals(constraintName)) {
                throw new ConflictException("Email already in use");
            }
            throw new ConflictException("Username or email already in use");
        }
    }

    private String extractConstraintName(DataIntegrityViolationException e) {
        Throwable cause = e.getCause();
        if (cause instanceof org.hibernate.exception.ConstraintViolationException cve) {
            return cve.getConstraintName();
        }
        return null;
    }
}
