package com.watchwise.watchwise_api.userlist.service;

import com.watchwise.watchwise_api.userlist.dto.UserListItemResponseDTO;

import java.util.List;

public record UserListItemsWithState(
        List<UserListItemResponseDTO> items,
        double watchedPercentage) {
}
