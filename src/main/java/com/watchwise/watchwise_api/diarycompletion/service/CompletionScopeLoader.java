package com.watchwise.watchwise_api.diarycompletion.service;

import com.watchwise.watchwise_api.content.dto.MediaReference;
import com.watchwise.watchwise_api.diarycompletion.model.VersionedCompletionScope;

import java.time.Instant;

public interface CompletionScopeLoader {

    VersionedCompletionScope load(MediaReference reference, String region, String language, Instant now);
}
