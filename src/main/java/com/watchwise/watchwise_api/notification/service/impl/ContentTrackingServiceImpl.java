package com.watchwise.watchwise_api.notification.service.impl;

import com.watchwise.watchwise_api.common.tmdb.TmdbClient;
import com.watchwise.watchwise_api.common.tmdb.TmdbMovieDetails;
import com.watchwise.watchwise_api.common.tmdb.TmdbTvDetails;
import com.watchwise.watchwise_api.common.transaction.NewTransactionExecutor;
import com.watchwise.watchwise_api.content.dto.ContentRefCreationDTO;
import com.watchwise.watchwise_api.content.dto.ContentRefDTO;
import com.watchwise.watchwise_api.content.entity.Content;
import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.content.repository.ContentRepository;
import com.watchwise.watchwise_api.content.service.ContentService;
import com.watchwise.watchwise_api.diaryentry.repository.DiaryEntryRepository;
import com.watchwise.watchwise_api.notification.entity.Notification;
import com.watchwise.watchwise_api.notification.entity.NotificationType;
import com.watchwise.watchwise_api.notification.entity.TrackedContentState;
import com.watchwise.watchwise_api.notification.repository.NotificationRepository;
import com.watchwise.watchwise_api.notification.repository.TrackedContentStateRepository;
import com.watchwise.watchwise_api.notification.service.ContentTrackingService;
import com.watchwise.watchwise_api.notification.tracking.ContentChangeDetector;
import com.watchwise.watchwise_api.notification.tracking.ContentChangeEvent;
import com.watchwise.watchwise_api.notification.tracking.TmdbDateParser;
import com.watchwise.watchwise_api.user.repository.UserRepository;
import com.watchwise.watchwise_api.watchlist.repository.WatchlistEntryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class ContentTrackingServiceImpl implements ContentTrackingService {

    private final WatchlistEntryRepository watchlistEntryRepository;
    private final DiaryEntryRepository diaryEntryRepository;
    private final TrackedContentStateRepository trackedContentStateRepository;
    private final NotificationRepository notificationRepository;
    private final UserRepository userRepository;
    private final ContentService contentService;
    private final ContentRepository contentRepository;
    private final TmdbClient tmdbClient;
    private final ContentChangeDetector contentChangeDetector;
    private final NewTransactionExecutor newTransactionExecutor;

    @Override
    public void trackContentChanges() {
        Map<UUID, Content> distinctTracked = new LinkedHashMap<>();
        watchlistEntryRepository.findDistinctTrackedContent()
                .forEach(content -> distinctTracked.put(content.getId(), content));
        diaryEntryRepository.findDistinctInProgressSeriesTmdbIds()
                .forEach(seriesTmdbId -> resolveSeriesContent(seriesTmdbId)
                        .ifPresent(content -> distinctTracked.put(content.getId(), content)));

        distinctTracked.values().forEach(this::processTrackedContent);
    }

    private Optional<Content> resolveSeriesContent(String seriesTmdbId) {
        try {
            ContentRefDTO contentRef = contentService.getOrCreateReference(new ContentRefCreationDTO(
                    seriesTmdbId, ContentType.SERIES, null, null, null, null, null, null, null, null, null));
            return contentRepository.findById(contentRef.id());
        } catch (RuntimeException e) {
            log.warn("Failed to resolve SERIES content reference for series {}: {}", seriesTmdbId, e.getMessage());
            return Optional.empty();
        }
    }

    private void processTrackedContent(Content content) {
        try {
            String lastKnownStatus = trackedContentStateRepository.findLastKnownStatusByContentId(content.getId()).orElse(null);
            if (isTerminal(content.getType(), lastKnownStatus)) {
                return;
            }
            if (content.getType() == ContentType.MOVIE) {
                processMovie(content);
            } else if (content.getType() == ContentType.SERIES) {
                processSeries(content);
            }
        } catch (RuntimeException e) {
            log.warn("Failed to process tracked content {} ({}): {}", content.getId(), content.getTmdbId(), e.getMessage());
        }
    }

    private boolean isTerminal(ContentType type, String lastKnownStatus) {
        if (lastKnownStatus == null) {
            return false;
        }
        return switch (type) {
            case MOVIE -> ContentChangeDetector.RELEASED_STATUS.equals(lastKnownStatus)
                    || ContentChangeDetector.CANCELED_STATUS.equals(lastKnownStatus);
            case SERIES -> ContentChangeDetector.ENDED_STATUS.equals(lastKnownStatus)
                    || ContentChangeDetector.CANCELED_STATUS.equals(lastKnownStatus);
            default -> false;
        };
    }

    private void processMovie(Content content) {
        Optional<TmdbMovieDetails> fresh = tmdbClient.getMovieDetails(content.getTmdbId());
        if (fresh.isEmpty()) {
            return;
        }

        newTransactionExecutor.runInNewTransaction(() -> {
            TrackedContentState previous = trackedContentStateRepository.findByContentId(content.getId()).orElse(null);
            Optional<ContentChangeEvent> event = contentChangeDetector.detectMovieChange(previous, fresh.get(), LocalDate.now());

            event.ifPresent(e -> notifyWatchers(content, e));
            saveMovieState(content, previous, fresh.get());
            return null;
        });
    }

    private void processSeries(Content content) {
        Optional<TmdbTvDetails> fresh = tmdbClient.getTvDetails(content.getTmdbId());
        if (fresh.isEmpty()) {
            return;
        }

        newTransactionExecutor.runInNewTransaction(() -> {
            TrackedContentState previous = trackedContentStateRepository.findByContentId(content.getId()).orElse(null);
            List<ContentChangeEvent> events = contentChangeDetector.detectTvChange(previous, fresh.get(), LocalDate.now());

            events.forEach(e -> notifyWatchers(content, e));
            saveSeriesState(content, previous, fresh.get());
            return null;
        });
    }

    private void notifyWatchers(Content content, ContentChangeEvent event) {
        List<UUID> userIds = event.type() == NotificationType.NEW_EPISODE
                ? diaryEntryRepository.findUserIdsWatchingSeries(content.getTmdbId())
                : watchlistEntryRepository.findUserIdsByContentId(content.getId());

        LocalDateTime now = LocalDateTime.now();
        userIds.forEach(userId -> notificationRepository.save(Notification.builder()
                .user(userRepository.getReferenceById(userId))
                .type(event.type())
                .message(buildMessage(content, event))
                .content(content)
                .isRead(false)
                .createdAt(now)
                .updatedAt(now)
                .build()));
    }

    private String buildMessage(Content content, ContentChangeEvent event) {
        return switch (event.type()) {
            case RELEASE -> "New release available";
            case ANNOUNCED_DATE -> "Release date announced: " + event.relevantDate();
            case CANCELLED -> "This title was cancelled";
            case RENEWED -> "This series was renewed";
            case NEW_EPISODE -> "New episode available (S" + event.seasonNumber() + "E" + event.episodeNumber() + ")";
            case FOLLOWED_PERSON_NEW_CREDIT -> "New title from someone you follow";
        };
    }

    private void saveMovieState(Content content, TrackedContentState previous, TmdbMovieDetails fresh) {
        LocalDate releaseDate = TmdbDateParser.parseDate(fresh.releaseDate());
        TrackedContentState state = previous != null ? previous : TrackedContentState.builder().content(content).build();
        state.setLastKnownReleaseDate(releaseDate);
        state.setLastKnownStatus(fresh.status());
        state.setLastCheckedAt(LocalDateTime.now());
        trackedContentStateRepository.save(state);
    }

    private void saveSeriesState(Content content, TrackedContentState previous, TmdbTvDetails fresh) {
        TrackedContentState state = previous != null ? previous : TrackedContentState.builder().content(content).build();
        state.setLastKnownStatus(fresh.status());
        if (fresh.nextEpisodeToAir() != null) {
            state.setNextEpisodeAirDate(TmdbDateParser.parseDate(fresh.nextEpisodeToAir().airDate()));
            state.setNextEpisodeSeasonNumber(fresh.nextEpisodeToAir().seasonNumber());
            state.setNextEpisodeNumber(fresh.nextEpisodeToAir().episodeNumber());
        } else {
            state.setNextEpisodeAirDate(null);
            state.setNextEpisodeSeasonNumber(null);
            state.setNextEpisodeNumber(null);
        }
        state.setLastCheckedAt(LocalDateTime.now());
        trackedContentStateRepository.save(state);
    }
}
