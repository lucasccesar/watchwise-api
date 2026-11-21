package com.watchwise.watchwise_api.userlist.dto;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record UserListOrderMoveDTO(
        @NotNull UUID movedId,
        @NotNull UUID anchorId,
        @NotNull UserListOrderSide side) {
}
