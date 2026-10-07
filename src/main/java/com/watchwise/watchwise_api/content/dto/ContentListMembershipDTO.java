package com.watchwise.watchwise_api.content.dto;

import com.watchwise.watchwise_api.userlist.entity.UserListVisibility;

import java.util.UUID;

public record ContentListMembershipDTO(
        UUID listId,
        String name,
        UserListVisibility visibility) {
}
