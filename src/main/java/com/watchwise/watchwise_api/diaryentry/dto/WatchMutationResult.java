package com.watchwise.watchwise_api.diaryentry.dto;

import java.util.UUID;

public record WatchMutationResult(UUID diaryEntryId, boolean inserted, long historyRevision) {
}
