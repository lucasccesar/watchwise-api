package com.watchwise.watchwise_api.userlist.service;

import com.watchwise.watchwise_api.content.dto.ContentStateDTO;

import java.util.Map;
import java.util.UUID;

public record UserListContentStateResult(
        Map<UUID, ContentStateDTO> stateByItemId,
        Map<UUID, Double> watchedPercentageByListId) {
}
