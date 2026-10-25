package com.watchwise.watchwise_api.userlist.service;

import java.util.UUID;

public record ImportedUserListItemResult(UUID contentId, UUID itemId, boolean inserted) {
}
