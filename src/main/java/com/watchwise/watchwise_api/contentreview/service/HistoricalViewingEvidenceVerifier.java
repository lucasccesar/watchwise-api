package com.watchwise.watchwise_api.contentreview.service;

import com.watchwise.watchwise_api.calendar.entity.CalendarScheduleCompleteness;
import com.watchwise.watchwise_api.calendar.repository.CalendarScheduleCompletenessRepository;
import com.watchwise.watchwise_api.common.exception.BadRequestException;
import com.watchwise.watchwise_api.common.exception.ConflictException;
import com.watchwise.watchwise_api.common.exception.NotFoundException;
import com.watchwise.watchwise_api.content.dto.MediaReference;
import com.watchwise.watchwise_api.content.entity.Content;
import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.content.repository.ContentRepository;
import com.watchwise.watchwise_api.platformimport.model.ConfirmedWatchScopeSnapshot;
import com.watchwise.watchwise_api.diarycompletion.entity.UserSeriesHistoryState;
import com.watchwise.watchwise_api.diarycompletion.model.EpisodeCoordinate;
import com.watchwise.watchwise_api.diarycompletion.model.VersionedCompletionScope;
import com.watchwise.watchwise_api.diarycompletion.repository.UserSeriesHistoryStateRepository;
import com.watchwise.watchwise_api.diarycompletion.service.CompletionScopeLoader;
import com.watchwise.watchwise_api.diaryentry.repository.DiaryEntryRepository;
import com.watchwise.watchwise_api.user.entity.User;
import com.watchwise.watchwise_api.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class HistoricalViewingEvidenceVerifier {

    private final UserRepository userRepository;
    private final ContentRepository contentRepository;
    private final DiaryEntryRepository diaryEntryRepository;
    private final UserSeriesHistoryStateRepository historyStateRepository;
    private final CalendarScheduleCompletenessRepository completenessRepository;
    private final CompletionScopeLoader scopeLoader;
    private final Clock clock;

    public Verification verify(
            UUID ownerId,
            Content content,
            HistoricalViewingEvidence evidence,
            boolean requireCompleteScope,
            Instant now) {
        if (evidence == null) {
            throw new BadRequestException("Historical viewing evidence is required");
        }
        requireTargetMatches(content, evidence.content());
        User owner = userRepository.findById(ownerId)
                .orElseThrow(() -> new NotFoundException("User not found"));
        String seriesTmdbId = seriesTmdbId(content);
        long revisionBefore = historyRevision(ownerId, seriesTmdbId);
        VersionedCompletionScope versionedScope = scopeLoader.load(
                evidence.content(), owner.getPreferredRegion(), owner.getPreferredLanguage(), now);
        List<DiaryEntryRepository.EpisodeCompletionWatch> eventRows = loadEvents(ownerId, content, evidence);
        long scheduleVersion = scheduleVersion(content, owner);
        long revisionAfter = historyRevision(ownerId, seriesTmdbId);
        if (revisionBefore != revisionAfter) {
            throw new ConflictException("Diary evidence changed while historical evidence was checked");
        }

        Set<EpisodeCoordinate> eventCoordinates = new HashSet<>();
        boolean uniqueCoordinates = true;
        for (DiaryEntryRepository.EpisodeCompletionWatch event : eventRows) {
            EpisodeCoordinate coordinate = new EpisodeCoordinate(event.getSeasonNumber(), event.getEpisodeNumber());
            uniqueCoordinates &= eventCoordinates.add(coordinate);
        }
        boolean completeScope = versionedScope.scope().releaseComplete()
                && versionedScope.validUntil().isAfter(now)
                && versionedScope.scope().episodes().equals(eventCoordinates)
                && uniqueCoordinates
                && evidence.scopeHash().equals(versionedScope.scheduleVersion());
        if (requireCompleteScope && !completeScope) {
            throw new BadRequestException("Historical viewing evidence does not prove a complete pass");
        }
        return new Verification(
                revisionAfter, scheduleVersion, versionedScope.validUntil(), completeScope, evidence.eventIds());
    }

    public Verification verifyPreparedScope(
            UUID ownerId,
            Content content,
            HistoricalViewingEvidence evidence,
            ConfirmedWatchScopeSnapshot snapshot,
            boolean requireCompleteScope,
            Instant now) {
        if (evidence == null || snapshot == null || now == null) {
            throw new BadRequestException("A prepared historical scope is required");
        }
        requireTargetMatches(content, evidence.content());
        if (!snapshot.target().equals(evidence.content())
                || !snapshot.scheduleVersion().equals(evidence.scopeHash())
                || !snapshot.validUntil().isAfter(now)) {
            throw new ConflictException("The confirmed episode scope expired or changed before commit");
        }
        User owner = userRepository.findById(ownerId)
                .orElseThrow(() -> new NotFoundException("User not found"));
        if (!matchesLocalScheduleRevision(content, owner, snapshot)) {
            throw new ConflictException("Diary completion schedule changed after the episode scope was confirmed");
        }

        long revisionBefore = historyRevision(ownerId, seriesTmdbId(content));
        List<DiaryEntryRepository.EpisodeCompletionWatch> eventRows = loadEvents(ownerId, content, evidence);
        long revisionAfter = historyRevision(ownerId, seriesTmdbId(content));
        if (revisionBefore != revisionAfter) {
            throw new ConflictException("Diary evidence changed while historical evidence was checked");
        }

        Set<EpisodeCoordinate> expectedCoordinates = Set.copyOf(snapshot.episodes());
        Set<EpisodeCoordinate> eventCoordinates = new HashSet<>();
        boolean uniqueCoordinates = true;
        for (DiaryEntryRepository.EpisodeCompletionWatch event : eventRows) {
            uniqueCoordinates &= eventCoordinates.add(new EpisodeCoordinate(
                    event.getSeasonNumber(), event.getEpisodeNumber()));
        }
        boolean completeScope = snapshot.releaseComplete()
                && expectedCoordinates.equals(eventCoordinates)
                && uniqueCoordinates
                && evidence.watchNumber() > 0;
        if (requireCompleteScope && !completeScope) {
            throw new BadRequestException("Historical viewing evidence does not prove a complete pass");
        }
        return new Verification(revisionAfter, scheduleVersion(content, owner), snapshot.validUntil(),
                completeScope, evidence.eventIds());
    }

    public void revalidate(UUID ownerId, Content content, HistoricalViewingEvidence evidence, Verification verified) {
        if (evidence == null || verified == null) {
            throw new BadRequestException("Historical viewing evidence is required");
        }
        requireTargetMatches(content, evidence.content());
        if (historyRevision(ownerId, seriesTmdbId(content)) != verified.historyRevision()) {
            throw new ConflictException("Diary evidence changed while the historical opinion was being saved");
        }
        loadEvents(ownerId, content, evidence);
        User owner = userRepository.findById(ownerId)
                .orElseThrow(() -> new NotFoundException("User not found"));
        if (scheduleVersion(content, owner) != verified.scheduleVersion()) {
            throw new ConflictException("Diary completion schedule changed while the historical opinion was being saved");
        }
        if (verified.completionScopeProven() && !verified.validUntil().isAfter(clock.instant())) {
            throw new ConflictException("Diary completion scope expired while the historical opinion was being saved");
        }
    }

    public void revalidatePreparedScope(UUID ownerId, Content content, HistoricalViewingEvidence evidence,
                                        ConfirmedWatchScopeSnapshot snapshot, Verification verified) {
        if (evidence == null || snapshot == null || verified == null) {
            throw new BadRequestException("A prepared historical scope is required");
        }
        requireTargetMatches(content, evidence.content());
        if (!snapshot.target().equals(evidence.content())
                || !snapshot.scheduleVersion().equals(evidence.scopeHash())
                || !snapshot.validUntil().isAfter(clock.instant())) {
            throw new ConflictException("The confirmed episode scope expired before the opinion was saved");
        }
        if (historyRevision(ownerId, seriesTmdbId(content)) != verified.historyRevision()) {
            throw new ConflictException("Diary evidence changed while the historical opinion was being saved");
        }
        List<DiaryEntryRepository.EpisodeCompletionWatch> rows = loadEvents(ownerId, content, evidence);
        Set<EpisodeCoordinate> eventCoordinates = rows.stream()
                .map(event -> new EpisodeCoordinate(event.getSeasonNumber(), event.getEpisodeNumber()))
                .collect(Collectors.toSet());
        if (snapshot.releaseComplete() && !Set.copyOf(snapshot.episodes()).equals(eventCoordinates)) {
            throw new ConflictException("Diary completion evidence changed after the scope was confirmed");
        }
        User owner = userRepository.findById(ownerId)
                .orElseThrow(() -> new NotFoundException("User not found"));
        if (!matchesLocalScheduleRevision(content, owner, snapshot)
                || scheduleVersion(content, owner) != verified.scheduleVersion()) {
            throw new ConflictException("Diary completion schedule changed while the historical opinion was being saved");
        }
    }

    private List<DiaryEntryRepository.EpisodeCompletionWatch> loadEvents(
            UUID ownerId,
            Content content,
            HistoricalViewingEvidence evidence) {
        List<DiaryEntryRepository.EpisodeCompletionWatch> rows = diaryEntryRepository
                .findEpisodeCompletionWatchesByUserIdAndDiaryEntryIdIn(ownerId, evidence.eventIds());
        boolean valid = rows.size() == evidence.eventIds().size()
                && rows.stream().allMatch(event -> Objects.equals(event.getSeriesTmdbId(), seriesTmdbId(content))
                && Objects.equals(event.getWatchNumber(), evidence.watchNumber())
                && event.getSeasonNumber() != null && event.getSeasonNumber() > 0
                && event.getEpisodeNumber() != null && event.getEpisodeNumber() > 0
                && (content.getType() == ContentType.SERIES
                || Objects.equals(event.getSeasonNumber(), content.getSeasonNumber())));
        if (!valid) {
            throw new BadRequestException("Historical viewing evidence does not match this owner, content, and pass");
        }
        return rows;
    }

    private void requireTargetMatches(Content content, MediaReference reference) {
        if (content == null || !mediaReference(content).equals(reference)
                || (content.getType() != ContentType.SERIES && content.getType() != ContentType.SEASON)
                || (content.getType() == ContentType.SEASON
                && (content.getSeasonNumber() == null || content.getSeasonNumber() <= 0))) {
            throw new BadRequestException("Historical viewing evidence target does not match a series or regular season");
        }
    }

    private MediaReference mediaReference(Content content) {
        return switch (content.getType()) {
            case SERIES -> MediaReference.series(content.getTmdbId());
            case SEASON -> MediaReference.season(content.getSeriesTmdbId(), content.getSeasonNumber());
            default -> new MediaReference(content.getType(), content.getTmdbId(), content.getSeriesTmdbId(),
                    content.getSeasonNumber(), content.getEpisodeNumber());
        };
    }

    private long historyRevision(UUID ownerId, String seriesTmdbId) {
        return historyStateRepository.findById(new UserSeriesHistoryState.Key(ownerId, seriesTmdbId))
                .map(UserSeriesHistoryState::getHistoryRevision)
                .orElse(0L);
    }

    private long scheduleVersion(Content content, User owner) {
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

    private boolean matchesLocalScheduleRevision(Content content, User owner,
                                                 ConfirmedWatchScopeSnapshot snapshot) {
        CalendarScheduleCompleteness.GroupType groupType = content.getType() == ContentType.SERIES
                ? CalendarScheduleCompleteness.GroupType.SERIES
                : CalendarScheduleCompleteness.GroupType.SEASON;
        var marker = completenessRepository.findByGroupTypeAndSeriesTmdbIdAndSeasonNumberAndRegionAndLanguage(
                groupType,
                seriesTmdbId(content),
                content.getType() == ContentType.SERIES ? null : content.getSeasonNumber(),
                owner.getPreferredRegion(),
                owner.getPreferredLanguage());
        return marker.isPresent() == snapshot.localCalendarMarkerPresent()
                && marker.map(CalendarScheduleCompleteness::getScopeVersion)
                .orElse(0L) == snapshot.localCalendarScopeVersion();
    }

    private String seriesTmdbId(Content content) {
        return content.getType() == ContentType.SERIES ? content.getTmdbId() : content.getSeriesTmdbId();
    }

    public record Verification(
            long historyRevision,
            long scheduleVersion,
            Instant validUntil,
            boolean completionScopeProven,
            Set<UUID> eventIds) {

        public Verification {
            Objects.requireNonNull(validUntil, "validUntil is required");
            eventIds = Set.copyOf(eventIds);
        }
    }
}
