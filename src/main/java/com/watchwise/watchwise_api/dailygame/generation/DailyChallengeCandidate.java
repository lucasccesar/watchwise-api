package com.watchwise.watchwise_api.dailygame.generation;

import com.watchwise.watchwise_api.dailygame.entity.DailyGameTargetKind;
import com.watchwise.watchwise_api.dailygame.entity.DailyGameType;

import java.util.List;
import java.util.Map;

public record DailyChallengeCandidate(
        DailyGameType gameType,
        DailyGameTargetKind targetKind,
        String targetTmdbId,
        String seriesTmdbId,
        Integer seasonNumber,
        Integer episodeNumber,
        String sourceTmdbId,
        String answerKey,
        String imagePath,
        Map<String, Object> answerSnapshot,
        Map<String, Object> displaySnapshot,
        List<HintSnapshot> hints) {

    public DailyChallengeCandidate {
        if (gameType == null || targetKind == null || answerKey == null || answerKey.isBlank()
                || imagePath == null || imagePath.isBlank() || answerSnapshot == null || displaySnapshot == null) {
            throw new IllegalArgumentException("Daily challenge candidate is incomplete");
        }
        hints = hints == null ? List.of() : List.copyOf(hints);
    }

    public record HintSnapshot(String hintType, String hintValue) {

        public HintSnapshot {
            if (hintType == null || hintType.isBlank() || hintValue == null || hintValue.isBlank()) {
                throw new IllegalArgumentException("Daily challenge hint is incomplete");
            }
        }
    }
}
