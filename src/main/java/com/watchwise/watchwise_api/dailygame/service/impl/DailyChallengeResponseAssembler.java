package com.watchwise.watchwise_api.dailygame.service.impl;

import com.watchwise.watchwise_api.common.tmdb.TmdbImageUrlBuilder;
import com.watchwise.watchwise_api.dailygame.dto.DailyGameAnswerDTO;
import com.watchwise.watchwise_api.dailygame.dto.DailyGameAttemptResponseDTO;
import com.watchwise.watchwise_api.dailygame.dto.DailyGameHintDTO;
import com.watchwise.watchwise_api.dailygame.dto.DailyGameGuessFeedbackDTO;
import com.watchwise.watchwise_api.dailygame.dto.DailyGameHistoryDTO;
import com.watchwise.watchwise_api.dailygame.dto.DailyGameStateDTO;
import com.watchwise.watchwise_api.dailygame.dto.DailyGameTodayResponseDTO;
import com.watchwise.watchwise_api.dailygame.dto.DailyGameViewStatus;
import com.watchwise.watchwise_api.dailygame.entity.DailyChallenge;
import com.watchwise.watchwise_api.dailygame.entity.DailyChallengeHint;
import com.watchwise.watchwise_api.dailygame.entity.DailyGameResultStatus;
import com.watchwise.watchwise_api.dailygame.entity.DailyGameTargetKind;
import com.watchwise.watchwise_api.dailygame.entity.UserDailyGameResult;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;

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
        return toAttemptResponse(challenge, result, allHints, null);
    }

    public DailyGameAttemptResponseDTO toAttemptResponse(
            DailyChallenge challenge,
            UserDailyGameResult result,
            List<DailyChallengeHint> allHints,
            DailyGameGuessFeedbackDTO guessFeedback) {
        DailyGameView view = view(challenge, result, allHints);
        return new DailyGameAttemptResponseDTO(
                view.gameType(), view.targetKind(), view.maxAttempts(), view.attemptsUsed(), view.attemptsRemaining(),
                view.status(), view.imageUrl(), view.hints(), view.score(), view.completedAt(), view.answer(),
                view.visibleImageUrls(), view.imageUrls(), guessFeedback);
    }

    public DailyGameHistoryDTO toHistoryResponse(
            LocalDate challengeDate, DailyChallenge challenge, UserDailyGameResult result) {
        DailyGameView view = view(challenge, result, List.of());
        return new DailyGameHistoryDTO(
                challengeDate, view.gameType(), view.targetKind(), view.maxAttempts(), view.status(),
                view.attemptsUsed(), view.score(), view.completedAt(), answer(challenge));
    }

    private DailyGameStateDTO toState(
            DailyChallenge challenge,
            UserDailyGameResult result,
            List<DailyChallengeHint> allHints) {
        DailyGameView view = view(challenge, result, allHints);
        return new DailyGameStateDTO(
                view.gameType(), view.targetKind(), view.maxAttempts(), view.attemptsUsed(), view.attemptsRemaining(),
                view.status(), view.imageUrl(), view.hints(), view.score(), view.completedAt(), view.answer(),
                view.visibleImageUrls(), view.imageUrls());
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
        List<String> imagePaths = imagePaths(challenge);
        int currentImageIndex = Math.min(attemptsUsed, imagePaths.size() - 1);
        int visiblePositions = challenge.getTargetKind() == DailyGameTargetKind.EPISODE
                ? attemptsUsed + 1 : 1;
        List<String> visibleImageUrls = IntStream.range(0, visiblePositions)
                .mapToObj(index -> imageUrl(challenge, imagePaths.get(Math.min(index, imagePaths.size() - 1))))
                .toList();
        List<String> imageUrls = status == DailyGameViewStatus.COMPLETED
                && challenge.getTargetKind() == DailyGameTargetKind.EPISODE
                ? imagePaths.stream().map(path -> imageUrl(challenge, path)).toList()
                : null;
        return new DailyGameView(
                challenge.getGameType(), challenge.getTargetKind(), maxAttempts, attemptsUsed, attemptsRemaining,
                status, imageUrl(challenge, imagePaths.get(currentImageIndex)), hints, score,
                result == null ? null : result.getCompletedAt(), terminal ? answer(challenge) : null,
                visibleImageUrls, imageUrls);
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
        Map<String, Object> snapshot = challenge.getAnswerSnapshot();
        String title = snapshot == null ? null : (String) snapshot.get("title");
        return new DailyGameAnswerDTO(targetKind, tmdbId, personTmdbId, seriesTmdbId,
                challenge.getSeasonNumber(), challenge.getEpisodeNumber(), title, imageUrl(challenge));
    }

    private String imageUrl(DailyChallenge challenge) {
        return imageUrl(challenge, challenge.getImagePath());
    }

    private String imageUrl(DailyChallenge challenge, String imagePath) {
        if (isAbsoluteHttpUrl(imagePath)) {
            return imagePath;
        }
        return switch (challenge.getTargetKind()) {
            case MOVIE, SERIES -> TmdbImageUrlBuilder.posterUrl(imagePath);
            case PERSON -> TmdbImageUrlBuilder.profileUrl(imagePath);
            case EPISODE -> TmdbImageUrlBuilder.stillUrl(imagePath);
        };
    }

    private List<String> imagePaths(DailyChallenge challenge) {
        Map<String, Object> snapshot = challenge.getDisplaySnapshot();
        if (snapshot != null && snapshot.get("imagePaths") instanceof List<?> paths) {
            List<String> validPaths = paths.stream()
                    .filter(String.class::isInstance)
                    .map(String.class::cast)
                    .filter(path -> !path.isBlank())
                    .toList();
            if (!validPaths.isEmpty()) {
                return validPaths;
            }
        }
        return List.of(challenge.getImagePath());
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
            DailyGameAnswerDTO answer,
            List<String> visibleImageUrls,
            List<String> imageUrls) {
    }
}
