package com.watchwise.watchwise_api.user.service.impl;

import com.watchwise.watchwise_api.common.exception.BadRequestException;
import com.watchwise.watchwise_api.common.exception.ConflictException;
import com.watchwise.watchwise_api.common.exception.ForbiddenException;
import com.watchwise.watchwise_api.common.exception.NotFoundException;
import com.watchwise.watchwise_api.common.exception.UnauthorizedException;
import com.watchwise.watchwise_api.user.dto.LoginUserDTO;
import com.watchwise.watchwise_api.user.dto.PatchUserDTO;
import com.watchwise.watchwise_api.user.dto.PostUserDTO;
import com.watchwise.watchwise_api.user.dto.PublicUserDTO;
import com.watchwise.watchwise_api.user.dto.UserPreviewDTO;
import com.watchwise.watchwise_api.user.dto.UserResponseDTO;
import com.watchwise.watchwise_api.user.entity.User;
import com.watchwise.watchwise_api.user.mapper.UserMapper;
import com.watchwise.watchwise_api.user.repository.UserRepository;
import com.watchwise.watchwise_api.user.service.UserService;
import io.micrometer.common.util.StringUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {

    private final UserMapper userMapper;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    static final int DEFAULT_PAGE = 0;
    static final int DEFAULT_PAGE_SIZE = 20;

    @Override
    public UserResponseDTO saveNewUser(PostUserDTO postUserDTO) {

        User mapperUser = userMapper.postUserDtoToUser(postUserDTO);
        mapperUser.setPassword(passwordEncoder.encode(postUserDTO.password()));
        mapperUser.setEmail(postUserDTO.email().toLowerCase().trim());
        mapperUser.setUsername(postUserDTO.username().trim());

        try {
            return userMapper.userToUserResponseDto(userRepository.save(mapperUser));
        } catch (DataIntegrityViolationException e) {
            throw mapUniqueConstraintViolation(e);
        }
    }

    @Override
    public UserResponseDTO updateUser(UUID id, PatchUserDTO patchUserDTO) {
        User user = userRepository.findById(id).orElseThrow(() -> new NotFoundException("User not found"));

        applyPatch(user, patchUserDTO);

        try {
            return userMapper.userToUserResponseDto(userRepository.save(user));
        } catch (DataIntegrityViolationException e) {
            throw mapUniqueConstraintViolation(e);
        }
    }

    private void applyPatch(User user, PatchUserDTO patchUserDTO) {
        if (patchUserDTO.username() != null) {
            String newUsername = patchUserDTO.username().trim();
            if (!newUsername.equals(user.getUsername())) {
                user.setUsername(newUsername);
            }
        }

        if (patchUserDTO.email() != null) {
            String newEmail = patchUserDTO.email().trim().toLowerCase();
            if (!newEmail.equals(user.getEmail())) {
                user.setEmail(newEmail);
            }
        }

        if (patchUserDTO.password() != null && !passwordEncoder.matches(patchUserDTO.password(), user.getPassword())) {
            user.setPassword(passwordEncoder.encode(patchUserDTO.password()));
        }

        if (patchUserDTO.description() != null && !patchUserDTO.description().equals(user.getDescription())) {
            user.setDescription(patchUserDTO.description());
        }

        if (patchUserDTO.profilePicture() != null && !patchUserDTO.profilePicture().equals(user.getProfilePicture())) {
            user.setProfilePicture(patchUserDTO.profilePicture());
        }

        if (patchUserDTO.isProfilePublic() != null && !patchUserDTO.isProfilePublic().equals(user.getIsProfilePublic())) {
            user.setIsProfilePublic(patchUserDTO.isProfilePublic());
        }
    }

    private ConflictException mapUniqueConstraintViolation(DataIntegrityViolationException e) {
        String constraintName = extractConstraintName(e);

        if ("uq_users_username".equals(constraintName)) {
            return new ConflictException("Username already in use");
        }
        if ("uq_users_email".equals(constraintName)) {
            return new ConflictException("Email already in use");
        }
        return new ConflictException("Username or email already in use");
    }

    @Override
    public PublicUserDTO getUserById(UUID id) {
    User foundUser = userRepository.findById(id).orElseThrow(()->new NotFoundException("User not found"));

        if (!Boolean.TRUE.equals(foundUser.getIsProfilePublic())) {
            throw new ForbiddenException("This user profile is private");
        }

        return userMapper.userToPublicUserDto(foundUser);
    }

    @Override
    public Page<UserPreviewDTO> getUsersByUsername(String username, Integer pageNumber, Integer pageSize, Boolean isProfilePublic) {

        if (StringUtils.isEmpty(username)) {
            throw new BadRequestException("Username must be provided");
        }

        PageRequest pageRequest = buildPageRequest(pageNumber, pageSize, null, null);

        boolean onlyPublic = Boolean.TRUE.equals(isProfilePublic);

        return userRepository
                .findByUsernameStartingWithIgnoreCase(username, onlyPublic, pageRequest)
                .map(userMapper::userToUserPreviewDto);
    }

    public PageRequest buildPageRequest(Integer pageNumber, Integer pageSize, String sortBy, String sortDirection) {
        int queryPageNumber;
        int queryPageSize;
        Sort sort = null;

        if (pageNumber != null && pageNumber > 0) {
            queryPageNumber = pageNumber - 1;
        } else if (pageNumber == null || pageNumber == 0) {
            queryPageNumber = DEFAULT_PAGE;
        } else {
            throw new BadRequestException("Page number must be greater than or equal to 0");
        }

        if (pageSize == null || pageSize > 1000) {
            queryPageSize = DEFAULT_PAGE_SIZE;
        } else {
            if (pageSize <= 0) {
                throw new BadRequestException("Page size must be greater than 0");
            } else {
                queryPageSize = pageSize;
            }
        }

        if (!(sortBy == null)) {
            if (sortDirection == null || !sortDirection.equals("desc")) {
                sort = Sort.by(Sort.Order.asc(sortBy));
            } else {
                sort = Sort.by(Sort.Order.desc(sortBy));
            }
        }

        if (sort == null) {
            return PageRequest.of(queryPageNumber, queryPageSize);
        }
        return PageRequest.of(queryPageNumber, queryPageSize, sort);
    }

    @Override
    public UserResponseDTO login(LoginUserDTO loginUserDTO) {
        User user = userRepository
                .findByUsernameIgnoreCaseOrEmailIgnoreCase(loginUserDTO.identifier(), loginUserDTO.identifier())
                .orElseThrow(() -> new UnauthorizedException("Invalid credentials"));

        if (!passwordEncoder.matches(loginUserDTO.password(), user.getPassword())) {
            throw new UnauthorizedException("Invalid credentials");
        }

        return userMapper.userToUserResponseDto(user);
    }

    private String extractConstraintName(DataIntegrityViolationException e) {
        Throwable cause = e.getCause();
        if (cause instanceof org.hibernate.exception.ConstraintViolationException cve) {
            return cve.getConstraintName();
        }
        return null;
    }
}
