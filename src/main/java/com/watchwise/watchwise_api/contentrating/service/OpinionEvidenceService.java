package com.watchwise.watchwise_api.contentrating.service;

import com.watchwise.watchwise_api.calendar.entity.CalendarScheduleCompleteness;
import com.watchwise.watchwise_api.calendar.repository.CalendarScheduleCompletenessRepository;
import com.watchwise.watchwise_api.common.exception.BadRequestException;
import com.watchwise.watchwise_api.common.exception.ConflictException;
import com.watchwise.watchwise_api.common.exception.NotFoundException;
import com.watchwise.watchwise_api.content.entity.Content;
import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.diarycompletion.entity.DiaryCompletionProjection;
import com.watchwise.watchwise_api.diarycompletion.entity.UserSeriesHistoryState;
import com.watchwise.watchwise_api.diarycompletion.repository.DiaryCompletionProjectionRepository;
import com.watchwise.watchwise_api.diarycompletion.repository.UserSeriesHistoryStateRepository;
import com.watchwise.watchwise_api.diarycompletion.service.DiaryCompletionService;
import com.watchwise.watchwise_api.diaryentry.repository.DiaryEntryRepository;
import com.watchwise.watchwise_api.user.entity.User;
import com.watchwise.watchwise_api.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class OpinionEvidenceService {

    private final UserRepository userRepository;
    private final DiaryEntryRepository diaryEntryRepository;
    private final DiaryCompletionService diaryCompletionService;
    private final DiaryCompletionProjectionRepository projectionRepository;
    private final UserSeriesHistoryStateRepository historyStateRepository;
    private final CalendarScheduleCompletenessRepository completenessRepository;

    public Snapshot capture(UUID ownerId, Content content, boolean refreshCompletion, Instant now) {
        requireOpinionContent(content);
        User owner = userRepository.findById(ownerId)
                .orElseThrow(() -> new NotFoundException("User not found"));
        String seriesTmdbId = seriesTmdbId(content);
        long initialRevision = historyRevision(ownerId, seriesTmdbId);
        Set<UUID> initialEvents = watchedEvents(ownerId, content, seriesTmdbId).stream()
                .map(DiaryEntryRepository.EpisodeCompletionWatch::getDiaryEntryId)
                .collect(Collectors.toUnmodifiableSet());
        if (refreshCompletion && !initialEvents.isEmpty()) {
            diaryCompletionService.refreshStaleScopes(ownerId, now);
        }

        long currentRevision = historyRevision(ownerId, seriesTmdbId);
        List<DiaryEntryRepository.EpisodeCompletionWatch> currentRows = watchedEvents(ownerId, content, seriesTmdbId);
        Set<UUID> currentEvents = currentRows.stream()
                .map(DiaryEntryRepository.EpisodeCompletionWatch::getDiaryEntryId)
                .collect(Collectors.toUnmodifiableSet());
        if (initialRevision != currentRevision || !initialEvents.equals(currentEvents)) {
            throw new ConflictException("Diary evidence changed while eligibility was checked");
        }

        DiaryCompletionProjection latestPass = refreshCompletion
                ? projectionRepository.findByUserIdAndContentIdOrderByWatchNumber(ownerId, content.getId()).stream()
                        .filter(projection -> projection.getValidUntil() != null
                                && projection.getValidUntil().isAfter(now)
                                && Objects.equals(projection.getHistoryRevision(), currentRevision))
                        .max(java.util.Comparator.comparing(DiaryCompletionProjection::getWatchNumber))
                        .orElse(null)
                : null;
        long scheduleVersion = latestPass == null
                ? currentScheduleVersion(content, owner)
                : latestPass.getScheduleVersion();
        if (latestPass != null && currentScheduleVersion(content, owner) != scheduleVersion) {
            throw new ConflictException("Diary completion schedule changed while eligibility was checked");
        }

        Set<UUID> completionEvents = latestPass == null
                ? Set.of()
                : currentRows.stream()
                        .filter(watch -> Objects.equals(watch.getWatchNumber(), latestPass.getWatchNumber()))
                        .map(DiaryEntryRepository.EpisodeCompletionWatch::getDiaryEntryId)
                        .collect(Collectors.toUnmodifiableSet());

        return new Snapshot(
                new OpinionEligibility(!currentEvents.isEmpty(), latestPass != null, false),
                currentRevision,
                scheduleVersion,
                latestPass == null ? null : latestPass.getWatchNumber(),
                currentEvents,
                completionEvents,
                refreshCompletion);
    }

    public void revalidate(UUID ownerId, Content content, Snapshot snapshot, Instant now) {
        requireOpinionContent(content);
        String seriesTmdbId = seriesTmdbId(content);
        List<DiaryEntryRepository.EpisodeCompletionWatch> currentRows = watchedEvents(ownerId, content, seriesTmdbId);
        Set<UUID> currentEvents = currentRows.stream()
                .map(DiaryEntryRepository.EpisodeCompletionWatch::getDiaryEntryId)
                .collect(Collectors.toUnmodifiableSet());
        Set<UUID> currentCompletionEvents = snapshot.completedWatchNumber() == null
                ? Set.of()
                : currentRows.stream()
                        .filter(watch -> Objects.equals(watch.getWatchNumber(), snapshot.completedWatchNumber()))
                        .map(DiaryEntryRepository.EpisodeCompletionWatch::getDiaryEntryId)
                        .collect(Collectors.toUnmodifiableSet());
        if (historyRevision(ownerId, seriesTmdbId) != snapshot.historyRevision()
                || !currentEvents.equals(snapshot.watchedEventIds())
                || !currentCompletionEvents.equals(snapshot.completionEventIds())) {
            throw new ConflictException("Diary evidence changed while the opinion was being saved");
        }
        if (!snapshot.completionRefreshed()) {
            return;
        }

        currentProjection(ownerId, content, snapshot, now);
        User owner = userRepository.findById(ownerId)
                .orElseThrow(() -> new NotFoundException("User not found"));
        if (currentScheduleVersion(content, owner) != snapshot.scheduleVersion()) {
            throw new ConflictException("Diary completion schedule changed while the opinion was being saved");
        }
    }

    private DiaryCompletionProjection currentProjection(
            UUID ownerId,
            Content content,
            Snapshot snapshot,
            Instant now) {
        if (snapshot.completedWatchNumber() == null) {
            return null;
        }
        return projectionRepository.findByUserIdAndContentIdAndWatchNumber(
                        ownerId, content.getId(), snapshot.completedWatchNumber()).stream()
                .filter(projection -> Objects.equals(projection.getHistoryRevision(), snapshot.historyRevision()))
                .filter(projection -> Objects.equals(projection.getScheduleVersion(), snapshot.scheduleVersion()))
                .filter(projection -> projection.getValidUntil() != null && projection.getValidUntil().isAfter(now))
                .findFirst()
                .orElseThrow(() -> new ConflictException("Diary completion changed while the opinion was being saved"));
    }

    private List<DiaryEntryRepository.EpisodeCompletionWatch> watchedEvents(
            UUID ownerId, Content content, String seriesTmdbId) {
        return diaryEntryRepository.findEpisodeCompletionWatches(ownerId, List.of(seriesTmdbId)).stream()
                .filter(watch -> Objects.equals(watch.getSeriesTmdbId(), seriesTmdbId))
                .filter(watch -> watch.getSeasonNumber() != null && watch.getSeasonNumber() > 0)
                .filter(watch -> content.getType() == ContentType.SERIES
                        || Objects.equals(watch.getSeasonNumber(), content.getSeasonNumber()))
                .toList();
    }

    private long historyRevision(UUID ownerId, String seriesTmdbId) {
        return historyStateRepository.findById(new UserSeriesHistoryState.Key(ownerId, seriesTmdbId))
                .map(UserSeriesHistoryState::getHistoryRevision)
                .orElse(0L);
    }

    private long currentScheduleVersion(Content content, User owner) {
        CalendarScheduleCompleteness.GroupType groupType = content.getType() == ContentType.SERIES
                ? CalendarScheduleCompleteness.GroupType.SERIES
                : CalendarScheduleCompleteness.GroupType.SEASON;
        return completenessRepository.findByGroupTypeAndSeriesTmdbIdAndSeasonNumberAndRegionAndLanguage(
                        groupType,
                        seriesTmdbId(content),
                        content.getType() == ContentType.SERIES ? null : content.getSeasonNumber(),
                        owner.getPreferredRegion(),
                        owner.getPreferredLanguage())
                .map(CalendarScheduleCompleteness::getScopeVersion)
                .orElse(0L);
    }

    private String seriesTmdbId(Content content) {
        return content.getType() == ContentType.SERIES ? content.getTmdbId() : content.getSeriesTmdbId();
    }

    private void requireOpinionContent(Content content) {
        if (content == null || (content.getType() != ContentType.SERIES && content.getType() != ContentType.SEASON)
                || (content.getType() == ContentType.SEASON
                && (content.getSeasonNumber() == null || content.getSeasonNumber() <= 0))) {
            throw new BadRequestException("Ratings and written reviews require a series or regular season");
        }
    }

    public record Snapshot(
            OpinionEligibility eligibility,
            long historyRevision,
            long scheduleVersion,
            Integer completedWatchNumber,
            Set<UUID> watchedEventIds,
            Set<UUID> completionEventIds,
            boolean completionRefreshed) {

        public Snapshot {
            watchedEventIds = Set.copyOf(watchedEventIds);
            completionEventIds = Set.copyOf(completionEventIds);
        }
    }
}
