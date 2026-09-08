package com.watchwise.watchwise_api.search.dto;

import com.watchwise.watchwise_api.user.dto.UserPreviewDTO;

import java.util.List;

public record SearchResultDTO(
        List<SearchContentDTO> contents,
        List<SearchPersonDTO> people,
        List<SearchUserListDTO> lists,
        List<UserPreviewDTO> users
) {
}
