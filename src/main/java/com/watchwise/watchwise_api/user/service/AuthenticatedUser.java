package com.watchwise.watchwise_api.user.service;

import com.watchwise.watchwise_api.user.dto.UserResponseDTO;

/** User data and the auth-version snapshot captured while credentials or the OAuth account were checked. */
public record AuthenticatedUser(UserResponseDTO user, long authVersion) {
}
