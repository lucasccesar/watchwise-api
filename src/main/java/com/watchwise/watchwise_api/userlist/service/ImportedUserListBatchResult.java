package com.watchwise.watchwise_api.userlist.service;

import java.util.List;

public record ImportedUserListBatchResult(List<ImportedUserListItemResult> items, int nextPosition) {
    public ImportedUserListBatchResult {
        items = List.copyOf(items);
        if (nextPosition < 1) throw new IllegalArgumentException("Next user list position must be positive");
    }
}
