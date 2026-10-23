package com.watchwise.watchwise_api.diarycompletion.service.impl;

import com.watchwise.watchwise_api.diarycompletion.repository.UserSeriesHistoryStateRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Component
@RequiredArgsConstructor
public class DiaryProjectionInvalidator {

    private final UserSeriesHistoryStateRepository historyStateRepository;

    @Transactional
    public void markSeriesChanged(UUID ownerId, String seriesTmdbId) {
        historyStateRepository.incrementHistoryRevision(ownerId, seriesTmdbId);
    }
}
