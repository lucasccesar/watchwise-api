package com.watchwise.watchwise_api.dailygame.service.impl;

import com.watchwise.watchwise_api.common.tmdb.TmdbImageUrlBuilder;
import com.watchwise.watchwise_api.dailygame.dto.DailyGameAnswerDTO;
import com.watchwise.watchwise_api.dailygame.dto.DailyGameAttemptResponseDTO;
import com.watchwise.watchwise_api.dailygame.dto.DailyGameHintDTO;
import com.watchwise.watchwise_api.dailygame.dto.DailyGameStateDTO;
import com.watchwise.watchwise_api.dailygame.dto.DailyGameTodayResponseDTO;
import com.watchwise.watchwise_api.dailygame.dto.DailyGameViewStatus;
import com.watchwise.watchwise_api.dailygame.entity.DailyChallenge;
import com.watchwise.watchwise_api.dailygame.entity.DailyChallengeHint;
import com.watchwise.watchwise_api.dailygame.entity.DailyGameResultStatus;
import com.watchwise.watchwise_api.dailygame.entity.DailyGameTargetKind;
import com.watchwise.watchwise_api.dailygame.entity.UserDailyGameResult;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
public class DailyChallengeResponseAssembler {

    public DailyGameTodayResponseDTO toTodayResponse(
            LocalDate date,
            List<DailyChallenge> challenges,
            Map<UUID, UserDailyGameResult> resultsByChallengeId,
            Map<UUID, List<DailyChallengeHint>> hintsByChallengeId) {
        List<DailyGameStateDTO> games = challenges.stream()
                .map(challenge -> toState(
                        challenge,
                        resultsByChallengeId.get(challenge.getId()),
                        hintsByChallengeId.getOrDefault(challenge.getId(), List.of())))
                .toList();
        return new DailyGameTodayResponseDTO(date, games);
    }

    public DailyGameAttemptResponseDTO toAttemptResponse(
            DailyChallenge challenge, UserDailyGameResult result) {
        return toAttemptResponse(challenge, result, List.of());
    }

    public DailyGameAttemptResponseDTO toAttemptResponse(
            DailyChallenge challenge,
            UserDailyGameResult result,
            List<DailyChallengeHint> allHints) {
        DailyGameView view = view(challenge, result, allHints);
        return new DailyGameAttemptResponseDTO(
                view.gameType(), view.targetKind(), view.maxAttempts(), view.attemptsUsed(), view.attemptsRemaining(),
                view.status(), view.imageUrl(), view.hints(), view.score(), view.completedAt(), view.answer());
    }

    private DailyGameStateDTO toState(
            DailyChallenge challenge,
            UserDailyGameResult result,
            List<DailyChallengeHint> allHints) {
        DailyGameView view = view(challenge, result, allHints);
        return new DailyGameStateDTO(
                view.gameType(), view.targetKind(), view.maxAttempts(), view.attemptsUsed(), view.attemptsRemaining(),
                view.status(), view.imageUrl(), view.hints(), view.score(), view.completedAt(), view.answer());
    }

    private DailyGameView view(
            DailyChallenge challenge,
            UserDailyGameResult result,
            List<DailyChallengeHint> allHints) {
        DailyGameViewStatus status = status(result);
        int maxAttempts = challenge.getGameType().maxAttempts();
        int attemptsUsed = result == null ? 0 : result.getAttemptsUsed();
        int attemptsRemaining = Math.max(0, maxAttempts - attemptsUsed);
        int score = result == null ? 0 : result.getScore();
        boolean terminal = status == DailyGameViewStatus.COMPLETED || status == DailyGameViewStatus.FAILED;
        int visibleHintCount = terminal ? allHints.size() : Math.min(allHints.size(), Math.max(1, attemptsUsed + 1));
        List<DailyGameHintDTO> hints = allHints.stream()
                .limit(visibleHintCount)
                .map(hint -> new DailyGameHintDTO(hint.getPosition(), hint.getHintType(), hint.getHintValue()))
                .toList();
        return new DailyGameView(
                challenge.getGameType(), challenge.getTargetKind(), maxAttempts, attemptsUsed, attemptsRemaining,
                status, imageUrl(challenge), hints, score, result == null ? null : result.getCompletedAt(),
                terminal ? answer(challenge) : null);
    }

    private DailyGameViewStatus status(UserDailyGameResult result) {
        if (result == null) {
            return DailyGameViewStatus.NOT_PLAYED;
        }
        return switch (result.getStatus()) {
            case IN_PROGRESS -> DailyGameViewStatus.IN_PROGRESS;
            case COMPLETED -> DailyGameViewStatus.COMPLETED;
            case FAILED -> DailyGameViewStatus.FAILED;
        };
    }

    private DailyGameAnswerDTO answer(DailyChallenge challenge) {
        DailyGameTargetKind targetKind = challenge.getTargetKind();
        String tmdbId = targetKind == DailyGameTargetKind.MOVIE || targetKind == DailyGameTargetKind.SERIES
                ? challenge.getTargetTmdbId() : null;
        String personTmdbId = targetKind == DailyGameTargetKind.PERSON ? challenge.getTargetTmdbId() : null;
        String seriesTmdbId = targetKind == DailyGameTargetKind.EPISODE ? challenge.getSeriesTmdbId() : null;
        JsonNode snapshot = challenge.getAnswerSnapshot();
        String title = snapshot == null ? null : snapshot.path("title").asText(null);
        return new DailyGameAnswerDTO(targetKind, tmdbId, personTmdbId, seriesTmdbId,
                challenge.getSeasonNumber(), challenge.getEpisodeNumber(), title, imageUrl(challenge));
    }

    private String imageUrl(DailyChallenge challenge) {
        String imagePath = challenge.getImagePath();
        if (isAbsoluteHttpUrl(imagePath)) {
            return imagePath;
        }
        return switch (challenge.getTargetKind()) {
            case MOVIE, SERIES -> TmdbImageUrlBuilder.posterUrl(imagePath);
            case PERSON -> TmdbImageUrlBuilder.profileUrl(imagePath);
            case EPISODE -> TmdbImageUrlBuilder.stillUrl(imagePath);
        };
    }

    private boolean isAbsoluteHttpUrl(String value) {
        return value != null && (value.regionMatches(true, 0, "http://", 0, 7)
                || value.regionMatches(true, 0, "https://", 0, 8));
    }

    private record DailyGameView(
            com.watchwise.watchwise_api.dailygame.entity.DailyGameType gameType,
            DailyGameTargetKind targetKind,
            int maxAttempts,
            int attemptsUsed,
            int attemptsRemaining,
            DailyGameViewStatus status,
            String imageUrl,
            List<DailyGameHintDTO> hints,
            int score,
            java.time.LocalDateTime completedAt,
            DailyGameAnswerDTO answer) {
    }
}
