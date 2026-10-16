package com.watchwise.watchwise_api.dailygame.service.impl;

import com.watchwise.watchwise_api.common.tmdb.TmdbImageUrlBuilder;
import com.watchwise.watchwise_api.dailygame.dto.DailyGameAnswerDTO;
import com.watchwise.watchwise_api.dailygame.dto.DailyGameAttemptDTO;
import com.watchwise.watchwise_api.dailygame.dto.DailyGameAttemptResponseDTO;
import com.watchwise.watchwise_api.dailygame.dto.DailyGameActorGuessDTO;
import com.watchwise.watchwise_api.dailygame.dto.DailyGameFilmographyEntryDTO;
import com.watchwise.watchwise_api.dailygame.dto.DailyGameFilmographyFeedbackDTO;
import com.watchwise.watchwise_api.dailygame.dto.DailyGameFilmographyStateDTO;
import com.watchwise.watchwise_api.dailygame.dto.DailyGameHintDTO;
import com.watchwise.watchwise_api.dailygame.dto.DailyGameGuessFeedbackDTO;
import com.watchwise.watchwise_api.dailygame.dto.DailyGameHistoryDTO;
import com.watchwise.watchwise_api.dailygame.dto.DailyGameOverviewCardDTO;
import com.watchwise.watchwise_api.dailygame.dto.DailyGameStateDTO;
import com.watchwise.watchwise_api.dailygame.dto.DailyGameTodayResponseDTO;
import com.watchwise.watchwise_api.dailygame.dto.DailyGameViewStatus;
import com.watchwise.watchwise_api.dailygame.entity.DailyChallenge;
import com.watchwise.watchwise_api.dailygame.entity.DailyChallengeHint;
import com.watchwise.watchwise_api.dailygame.entity.DailyGameResultStatus;
import com.watchwise.watchwise_api.dailygame.entity.DailyGameTargetKind;
import com.watchwise.watchwise_api.dailygame.entity.DailyGameType;
import com.watchwise.watchwise_api.dailygame.entity.UserDailyGameResult;
import com.watchwise.watchwise_api.dailygame.service.DailyGameAttemptDetailsCodec;
import com.watchwise.watchwise_api.dailygame.service.DailyGameFilmographyEntryKey;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;

@Component
public class DailyChallengeResponseAssembler {

    private static final int MINIMUM_EPISODE_STILLS = 6;

    private final DailyGameAttemptDetailsCodec attemptDetailsCodec;

    public DailyChallengeResponseAssembler() {
        this(new DailyGameAttemptDetailsCodec());
    }

    public DailyChallengeResponseAssembler(DailyGameAttemptDetailsCodec attemptDetailsCodec) {
        this.attemptDetailsCodec = attemptDetailsCodec;
    }

    public DailyGameTodayResponseDTO toTodayResponse(
            LocalDate date,
            List<DailyChallenge> challenges,
            Map<UUID, UserDailyGameResult> resultsByChallengeId,
            Map<UUID, List<DailyChallengeHint>> hintsByChallengeId) {
        return toTodayResponse(date, null, challenges, resultsByChallengeId, hintsByChallengeId);
    }

    public DailyGameTodayResponseDTO toTodayResponse(
            LocalDate date,
            LocalDate currentDate,
            List<DailyChallenge> challenges,
            Map<UUID, UserDailyGameResult> resultsByChallengeId,
            Map<UUID, List<DailyChallengeHint>> hintsByChallengeId) {
        List<DailyGameStateDTO> games = challenges.stream()
                .map(challenge -> toState(
                        challenge,
                        resultsByChallengeId.get(challenge.getId()),
                        hintsByChallengeId.getOrDefault(challenge.getId(), List.of()),
                        date.equals(currentDate)))
                .toList();
        return new DailyGameTodayResponseDTO(date, games);
    }

    public DailyGameOverviewCardDTO toOverviewCard(
            DailyChallenge challenge, UserDailyGameResult result) {
        DailyGameView view = view(challenge, result, List.of());
        return new DailyGameOverviewCardDTO(
                view.gameType(), view.targetKind(), view.maxAttempts(), view.attemptsUsed(),
                view.attemptsRemaining(), view.status(), view.score(), view.imageUrl(), view.visibleImageUrls());
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
        return toAttemptResponse(challenge, result, allHints, guessFeedback, false);
    }

    public DailyGameAttemptResponseDTO toAttemptResponse(
            DailyChallenge challenge,
            UserDailyGameResult result,
            List<DailyChallengeHint> allHints,
            DailyGameGuessFeedbackDTO guessFeedback,
            boolean includeAttempts) {
        return toAttemptResponse(challenge, result, allHints, guessFeedback, includeAttempts, true, null);
    }

    public DailyGameAttemptResponseDTO toAttemptResponse(
            DailyChallenge challenge,
            UserDailyGameResult result,
            List<DailyChallengeHint> allHints,
            DailyGameGuessFeedbackDTO guessFeedback,
            boolean includeAttempts,
            boolean majorRoles) {
        return toAttemptResponse(
                challenge, result, allHints, guessFeedback, includeAttempts, majorRoles, null);
    }

    public DailyGameAttemptResponseDTO toAttemptResponse(
            DailyChallenge challenge,
            UserDailyGameResult result,
            List<DailyChallengeHint> allHints,
            DailyGameGuessFeedbackDTO guessFeedback,
            boolean includeAttempts,
            boolean majorRoles,
            DailyGameAttemptDTO currentAttempt) {
        DailyGameView view = view(challenge, result, allHints);
        boolean terminal = isTerminal(result);
        List<DailyGameAttemptDTO> attempts = attempts(result, includeAttempts, challenge, majorRoles, terminal);
        DailyGameAttemptDTO responseCurrentAttempt = sanitizeFilmographyAttempt(
                challenge, currentAttempt, majorRoles, terminal);
        return new DailyGameAttemptResponseDTO(
                view.gameType(), view.targetKind(), view.maxAttempts(), view.attemptsUsed(), view.attemptsRemaining(),
                view.status(), view.imageUrl(), view.hints(), view.score(), view.completedAt(), view.answer(),
                view.visibleImageUrls(), view.imageUrls(), attempts, responseCurrentAttempt,
                filmography(challenge, filmographyAttempts(result), majorRoles, terminal), guessFeedback,
                view.shareOnCompletion(), view.sharedToFeed());
    }

    public DailyGameHistoryDTO toHistoryResponse(
            LocalDate challengeDate, DailyChallenge challenge, UserDailyGameResult result) {
        DailyGameView view = view(challenge, result, List.of());
        return new DailyGameHistoryDTO(
                challengeDate, view.gameType(), view.targetKind(), view.maxAttempts(), view.status(),
                view.attemptsUsed(), view.score(), view.completedAt(), view.answer(), view.sharedToFeed());
    }

    private DailyGameStateDTO toState(
            DailyChallenge challenge,
            UserDailyGameResult result,
            List<DailyChallengeHint> allHints) {
        return toState(challenge, result, allHints, false);
    }

    public DailyGameStateDTO toState(
            DailyChallenge challenge,
            UserDailyGameResult result,
            List<DailyChallengeHint> allHints,
            boolean includeAttempts) {
        DailyGameView view = view(challenge, result, allHints);
        List<DailyGameAttemptDTO> attempts = attempts(
                result, includeAttempts, challenge, true, isTerminal(result));
        return new DailyGameStateDTO(
                view.gameType(), view.targetKind(), view.maxAttempts(), view.attemptsUsed(), view.attemptsRemaining(),
                view.status(), view.imageUrl(), view.hints(), view.score(), view.completedAt(), view.answer(),
                view.visibleImageUrls(), view.imageUrls(), attempts,
                filmography(challenge, filmographyAttempts(result), true, isTerminal(result)),
                view.shareOnCompletion(), view.sharedToFeed());
    }

    public DailyGameStateDTO toState(
            DailyChallenge challenge,
            UserDailyGameResult result,
            List<DailyChallengeHint> allHints,
            boolean includeAttempts,
            boolean majorRoles) {
        DailyGameView view = view(challenge, result, allHints);
        List<DailyGameAttemptDTO> attempts = attempts(
                result, includeAttempts, challenge, majorRoles, isTerminal(result));
        return new DailyGameStateDTO(
                view.gameType(), view.targetKind(), view.maxAttempts(), view.attemptsUsed(), view.attemptsRemaining(),
                view.status(), view.imageUrl(), view.hints(), view.score(), view.completedAt(), view.answer(),
                view.visibleImageUrls(), view.imageUrls(), attempts,
                filmography(challenge, filmographyAttempts(result), majorRoles, isTerminal(result)),
                view.shareOnCompletion(), view.sharedToFeed());
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
        boolean hiddenImageGame = hidesImageUntilTerminal(challenge.getGameType());
        boolean informationGame = isInformationGame(challenge.getGameType());
        int visibleHintCount = terminal ? allHints.size() : Math.min(allHints.size(), Math.max(1, attemptsUsed + 1));
        List<DailyGameHintDTO> hints = informationGame
                ? List.of()
                : allHints.stream()
                        .limit(visibleHintCount)
                        .map(hint -> new DailyGameHintDTO(hint.getPosition(), hint.getHintType(), hint.getHintValue()))
                        .toList();
        List<String> imagePaths = imagePaths(challenge);
        if (challenge.getTargetKind() == DailyGameTargetKind.EPISODE
                && imagePaths.size() < MINIMUM_EPISODE_STILLS) {
            throw new com.watchwise.watchwise_api.common.exception.DailyGamesUnavailableException();
        }
        int currentImageIndex = Math.min(attemptsUsed, imagePaths.size() - 1);
        int visiblePositions = challenge.getTargetKind() == DailyGameTargetKind.EPISODE
                ? attemptsUsed + 1 : 1;
        List<String> visibleImageUrls = hiddenImageGame && !terminal
                ? List.of()
                : IntStream.range(0, visiblePositions)
                        .mapToObj(index -> imageUrl(challenge, imagePaths.get(Math.min(index, imagePaths.size() - 1))))
                        .toList();
        List<String> imageUrls = status == DailyGameViewStatus.COMPLETED
                && challenge.getTargetKind() == DailyGameTargetKind.EPISODE
                ? imagePaths.stream().map(path -> imageUrl(challenge, path)).toList()
                : null;
        return new DailyGameView(
                challenge.getGameType(), challenge.getTargetKind(), maxAttempts, attemptsUsed, attemptsRemaining,
                status, hiddenImageGame && !terminal
                        ? null : imageUrl(challenge, imagePaths.get(currentImageIndex)), hints, score,
                result == null ? null : result.getCompletedAt(), terminal ? toAnswer(challenge) : null,
                visibleImageUrls, imageUrls, result != null && result.isShareOnCompletion(),
                result != null && result.getSharedAt() != null);
    }

    private boolean hidesImageUntilTerminal(DailyGameType gameType) {
        return gameType == DailyGameType.MOVIE_BY_INFO
                || gameType == DailyGameType.SERIES_BY_INFO
                || gameType == DailyGameType.ACTOR_BY_MOVIE_FILMOGRAPHY
                || gameType == DailyGameType.ACTOR_BY_SERIES_FILMOGRAPHY;
    }

    private boolean isInformationGame(DailyGameType gameType) {
        return gameType == DailyGameType.MOVIE_BY_INFO || gameType == DailyGameType.SERIES_BY_INFO;
    }

    private List<DailyGameAttemptDTO> attempts(
            UserDailyGameResult result,
            boolean includeAttempts,
            DailyChallenge challenge,
            boolean majorRoles,
            boolean terminal) {
        if (!includeAttempts || result == null) {
            return null;
        }
        return attemptDetailsCodec.read(result.getAttemptDetails()).stream()
                .map(attempt -> sanitizeFilmographyAttempt(challenge, attempt, majorRoles, terminal))
                .toList();
    }

    private DailyGameAttemptDTO sanitizeFilmographyAttempt(
            DailyChallenge challenge,
            DailyGameAttemptDTO attempt,
            boolean majorRoles,
            boolean terminal) {
        if (attempt == null || !isFilmographyGame(challenge) || attempt.filmographyFeedback() == null) {
            return attempt;
        }
        DailyGameFilmographyFeedbackDTO feedback = attempt.filmographyFeedback();
        boolean series = challenge.getGameType()
                == com.watchwise.watchwise_api.dailygame.entity.DailyGameType.ACTOR_BY_SERIES_FILMOGRAPHY;
        java.util.Set<String> sharedWorkKeys = new java.util.HashSet<>(series && majorRoles
                ? feedback.sharedMajorRoleWorkKeys() : feedback.sharedAllRoleWorkKeys());
        Map<String, SnapshotFilmographyEntry> entriesByWorkId = new java.util.HashMap<>();
        Map<String, SnapshotFilmographyEntry> entriesByEntryKey = new java.util.HashMap<>();
        Object rawFilmography = challenge.getAnswerSnapshot() == null ? null
                : challenge.getAnswerSnapshot().get("filmography");
        if (rawFilmography instanceof List<?> values) {
            for (int index = 0; index < values.size(); index++) {
                if (!(values.get(index) instanceof Map<?, ?> raw)) {
                    continue;
                }
                String workId = string(raw.get("workId"));
                String mediaType = string(raw.get("mediaType"));
                String expectedMediaType = series ? "tv" : "movie";
                if (mediaType == null) {
                    mediaType = expectedMediaType;
                }
                Integer episodeCount = integer(raw.get("episodeCount"));
                Integer totalEpisodes = integer(raw.get("totalEpisodes"));
                if (workId == null || !expectedMediaType.equalsIgnoreCase(mediaType)
                        || (series && (episodeCount == null || episodeCount < 1
                        || (majorRoles && !isMajorRole(episodeCount, totalEpisodes))))) {
                    continue;
                }
                String workKey = (series ? "SERIES:" : "MOVIE:") + workId;
                String entryKey = DailyGameFilmographyEntryKey.forSnapshotEntry(challenge.getId(), index);
                SnapshotFilmographyEntry snapshotEntry = new SnapshotFilmographyEntry(
                        workId, string(raw.get("title")), integer(raw.get("year")),
                        strings(raw.get("genres")), episodeCount, workKey, entryKey);
                entriesByWorkId.putIfAbsent(workId, snapshotEntry);
                entriesByEntryKey.put(entryKey, snapshotEntry);
            }
        }
        List<DailyGameFilmographyEntryDTO> sanitizedEntries = feedback.entries().stream()
                .map(entry -> sanitizeFilmographyEntry(
                        entry, entriesByWorkId, entriesByEntryKey, sharedWorkKeys, terminal))
                .filter(java.util.Objects::nonNull)
                .toList();
        DailyGameFilmographyFeedbackDTO sanitizedFeedback = new DailyGameFilmographyFeedbackDTO(
                feedback.guessedActor(), feedback.sharedMajorRoleWorkKeys(),
                feedback.sharedAllRoleWorkKeys(), sanitizedEntries);
        return new DailyGameAttemptDTO(attempt.attemptNumber(), attempt.candidate(), attempt.episodeFeedback(),
                attempt.infoFeedback(), sanitizedFeedback);
    }

    private DailyGameFilmographyEntryDTO sanitizeFilmographyEntry(
            DailyGameFilmographyEntryDTO entry,
            Map<String, SnapshotFilmographyEntry> entriesByWorkId,
            Map<String, SnapshotFilmographyEntry> entriesByEntryKey,
            java.util.Set<String> sharedWorkKeys,
            boolean terminal) {
        SnapshotFilmographyEntry snapshotEntry = entry.entryKey() == null
                ? null : entriesByEntryKey.get(entry.entryKey());
        if (snapshotEntry == null && entry.workId() != null) {
            snapshotEntry = entriesByWorkId.get(entry.workId());
        }
        if (snapshotEntry == null) {
            return null;
        }
        boolean highlighted = sharedWorkKeys.contains(snapshotEntry.workKey());
        boolean revealed = terminal || highlighted;
        return new DailyGameFilmographyEntryDTO(
                revealed ? snapshotEntry.workId() : null,
                revealed ? snapshotEntry.title() : null,
                revealed,
                highlighted,
                snapshotEntry.year(),
                snapshotEntry.genres(),
                null,
                snapshotEntry.episodeCount(),
                null,
                null,
                snapshotEntry.entryKey());
    }

    private boolean isFilmographyGame(DailyChallenge challenge) {
        return challenge != null && (challenge.getGameType()
                == com.watchwise.watchwise_api.dailygame.entity.DailyGameType.ACTOR_BY_MOVIE_FILMOGRAPHY
                || challenge.getGameType()
                == com.watchwise.watchwise_api.dailygame.entity.DailyGameType.ACTOR_BY_SERIES_FILMOGRAPHY);
    }

    private record SnapshotFilmographyEntry(
            String workId,
            String title,
            Integer year,
            List<String> genres,
            Integer episodeCount,
            String workKey,
            String entryKey) {
    }

    private DailyGameFilmographyStateDTO filmography(
            DailyChallenge challenge,
            List<DailyGameAttemptDTO> attempts,
            boolean majorRoles,
            boolean terminal) {
        boolean series = challenge.getGameType()
                == com.watchwise.watchwise_api.dailygame.entity.DailyGameType.ACTOR_BY_SERIES_FILMOGRAPHY;
        if (!series && challenge.getGameType()
                != com.watchwise.watchwise_api.dailygame.entity.DailyGameType.ACTOR_BY_MOVIE_FILMOGRAPHY) {
            return null;
        }
        List<DailyGameFilmographyFeedbackDTO> feedback = (attempts == null ? List.<DailyGameAttemptDTO>of() : attempts)
                .stream().map(DailyGameAttemptDTO::filmographyFeedback)
                .filter(java.util.Objects::nonNull).toList();
        List<DailyGameActorGuessDTO> guessedActors = feedback.stream()
                .map(DailyGameFilmographyFeedbackDTO::guessedActor)
                .filter(java.util.Objects::nonNull).toList();
        java.util.Set<String> revealed = feedback.stream()
                .flatMap(value -> (series && majorRoles
                        ? value.sharedMajorRoleWorkKeys() : value.sharedAllRoleWorkKeys()).stream())
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        Object rawFilmography = challenge.getAnswerSnapshot() == null ? null
                : challenge.getAnswerSnapshot().get("filmography");
        List<DailyGameFilmographyEntryDTO> entries = rawFilmography instanceof List<?> values
                ? IntStream.range(0, values.size())
                        .mapToObj(index -> filmographyEntry(
                                challenge, values.get(index), index, series, majorRoles, revealed, terminal))
                        .filter(java.util.Objects::nonNull)
                        .sorted(Comparator.comparing(DailyGameFilmographyEntryDTO::year,
                                Comparator.nullsLast(Comparator.reverseOrder())))
                        .toList() : List.of();
        return new DailyGameFilmographyStateDTO(majorRoles, guessedActors, entries);
    }

    private DailyGameFilmographyEntryDTO filmographyEntry(
            DailyChallenge challenge,
            Object value,
            int snapshotIndex,
            boolean series,
            boolean majorRoles,
            java.util.Set<String> revealed,
            boolean terminal) {
        if (!(value instanceof Map<?, ?> raw)) return null;
        String workId = string(raw.get("workId"));
        String mediaType = string(raw.get("mediaType"));
        String title = string(raw.get("title"));
        if (workId == null || title == null || (series && !"tv".equalsIgnoreCase(mediaType))
                || (!series && !"movie".equalsIgnoreCase(mediaType))) return null;
        Integer episodeCount = integer(raw.get("episodeCount"));
        Integer totalEpisodes = integer(raw.get("totalEpisodes"));
        if (series && (episodeCount == null || episodeCount < 1
                || (majorRoles && !isMajorRole(episodeCount, totalEpisodes)))) return null;
        boolean highlighted = revealed.contains((series ? "SERIES:" : "MOVIE:") + workId);
        boolean isRevealed = terminal || highlighted;
        return new DailyGameFilmographyEntryDTO(isRevealed ? workId : null, isRevealed ? title : null,
                isRevealed, highlighted,
                integer(raw.get("year")), strings(raw.get("genres")), null,
                episodeCount, null, null,
                DailyGameFilmographyEntryKey.forSnapshotEntry(challenge.getId(), snapshotIndex));
    }

    private List<DailyGameAttemptDTO> filmographyAttempts(UserDailyGameResult result) {
        return result == null ? List.of() : attemptDetailsCodec.read(result.getAttemptDetails());
    }

    private boolean isTerminal(UserDailyGameResult result) {
        return result != null && (result.getStatus() == DailyGameResultStatus.COMPLETED
                || result.getStatus() == DailyGameResultStatus.FAILED);
    }

    private boolean isMajorRole(int episodeCount, Integer totalEpisodes) {
        if (totalEpisodes == null || totalEpisodes < 1) return false;
        double ratio = totalEpisodes <= 6 ? .33d : totalEpisodes <= 20 ? .40d : .50d;
        return episodeCount >= (int) Math.ceil(totalEpisodes * ratio);
    }

    private Integer integer(Object value) {
        if (value instanceof Number number) return number.intValue();
        try { return value == null ? null : Integer.valueOf(String.valueOf(value)); }
        catch (NumberFormatException ignored) { return null; }
    }

    private String string(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private List<String> strings(Object value) {
        return value instanceof List<?> values ? values.stream().map(this::string)
                .filter(java.util.Objects::nonNull).toList() : List.of();
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

    public DailyGameAnswerDTO toAnswer(DailyChallenge challenge) {
        DailyGameTargetKind targetKind = challenge.getTargetKind();
        String tmdbId = targetKind == DailyGameTargetKind.MOVIE || targetKind == DailyGameTargetKind.SERIES
                ? challenge.getTargetTmdbId() : null;
        String personTmdbId = targetKind == DailyGameTargetKind.PERSON ? challenge.getTargetTmdbId() : null;
        String seriesTmdbId = targetKind == DailyGameTargetKind.EPISODE ? challenge.getSeriesTmdbId() : null;
        Map<String, Object> snapshot = challenge.getAnswerSnapshot();
        String title = snapshotValue(snapshot, "episodeName");
        if (title == null) {
            title = snapshotValue(snapshot, "title");
        }
        String seriesName = targetKind == DailyGameTargetKind.EPISODE
                ? snapshotValue(snapshot, "seriesName") : null;
        String seriesPosterPath = targetKind == DailyGameTargetKind.EPISODE
                ? snapshotValue(snapshot, "seriesPosterPath") : null;
        Integer seriesYear = targetKind == DailyGameTargetKind.EPISODE
                ? snapshotInteger(snapshot, "seriesYear") : null;
        String answerImageUrl = imageUrl(challenge);
        return new DailyGameAnswerDTO(targetKind, tmdbId, personTmdbId, seriesTmdbId,
                challenge.getSeasonNumber(), challenge.getEpisodeNumber(), title, answerImageUrl, seriesName,
                posterUrl(seriesPosterPath), seriesYear);
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

    private String posterUrl(String imagePath) {
        if (isAbsoluteHttpUrl(imagePath)) {
            return imagePath;
        }
        return TmdbImageUrlBuilder.posterUrl(imagePath);
    }

    private String snapshotValue(Map<String, Object> snapshot, String key) {
        if (snapshot == null) {
            return null;
        }
        Object value = snapshot.get(key);
        return value instanceof String string && !string.isBlank() ? string : null;
    }

    private Integer snapshotInteger(Map<String, Object> snapshot, String key) {
        if (snapshot == null) {
            return null;
        }
        Object value = snapshot.get(key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String string) {
            try {
                return Integer.valueOf(string);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private List<String> imagePaths(DailyChallenge challenge) {
        Map<String, Object> snapshot = challenge.getDisplaySnapshot();
        if (snapshot != null && snapshot.get("imagePaths") instanceof List<?> paths) {
            List<String> validPaths = paths.stream()
                    .filter(String.class::isInstance)
                    .map(String.class::cast)
                    .filter(path -> !path.isBlank())
                    .distinct()
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
            List<String> imageUrls,
            boolean shareOnCompletion,
            boolean sharedToFeed) {
    }
}
