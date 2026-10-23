package com.watchwise.watchwise_api.diarycompletion.service;

import com.watchwise.watchwise_api.content.dto.MediaReference;

import java.time.Instant;
import java.util.UUID;

public interface DiaryCompletionService {

    void refreshStaleScopes(UUID ownerId, Instant now);

    boolean isComplete(UUID ownerId, MediaReference reference, int watchNumber);
}
