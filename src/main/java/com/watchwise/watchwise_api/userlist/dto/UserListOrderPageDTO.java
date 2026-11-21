package com.watchwise.watchwise_api.userlist.dto;

import java.util.List;

public record UserListOrderPageDTO(String revision, List<UserListOrderItemDTO> items, String nextCursor) {
    public UserListOrderPageDTO {
        items = items == null ? List.of() : List.copyOf(items);
    }
}
