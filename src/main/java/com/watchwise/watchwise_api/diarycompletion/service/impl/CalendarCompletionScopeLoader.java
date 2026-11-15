package com.watchwise.watchwise_api.diarycompletion.service.impl;

import com.watchwise.watchwise_api.calendar.service.CalendarEpisodeSchedule;
import com.watchwise.watchwise_api.calendar.service.CalendarScheduleBatch;
import com.watchwise.watchwise_api.calendar.service.CalendarScheduleCadence;
import com.watchwise.watchwise_api.calendar.service.CalendarScheduleKey;
import com.watchwise.watchwise_api.calendar.service.CalendarScheduleLookup;
import com.watchwise.watchwise_api.calendar.service.CalendarScheduleProvider;
import com.watchwise.watchwise_api.calendar.service.CalendarSeasonSchedule;
import com.watchwise.watchwise_api.calendar.service.CalendarSeriesSchedule;
import com.watchwise.watchwise_api.calendar.entity.CalendarScheduleCompleteness;
import com.watchwise.watchwise_api.calendar.entity.CalendarScheduleSnapshot;
import com.watchwise.watchwise_api.calendar.repository.CalendarScheduleCompletenessRepository;
import com.watchwise.watchwise_api.calendar.repository.CalendarScheduleSnapshotRepository;
import com.watchwise.watchwise_api.common.exception.BadRequestException;
import com.watchwise.watchwise_api.common.exception.NotFoundException;
import com.watchwise.watchwise_api.common.exception.TmdbUnavailableException;
import com.watchwise.watchwise_api.content.dto.MediaReference;
import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.diarycompletion.model.CompletionScope;
import com.watchwise.watchwise_api.diarycompletion.model.EpisodeCoordinate;
import com.watchwise.watchwise_api.diarycompletion.model.VersionedCompletionScope;
import com.watchwise.watchwise_api.diarycompletion.service.CompletionScopeLoader;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

@Service
public class CalendarCompletionScopeLoader implements CompletionScopeLoader {

    private final CalendarScheduleProvider scheduleProvider;
    private final CalendarScheduleCompletenessRepository completenessRepository;
    private final CalendarScheduleSnapshotRepository snapshotRepository;

    public CalendarCompletionScopeLoader(CalendarScheduleProvider scheduleProvider) {
        this.scheduleProvider = scheduleProvider;
        this.completenessRepository = null;
        this.snapshotRepository = null;
    }

    @Autowired
    public CalendarCompletionScopeLoader(
            CalendarScheduleProvider scheduleProvider,
            CalendarScheduleCompletenessRepository completenessRepository,
            CalendarScheduleSnapshotRepository snapshotRepository) {
        this.scheduleProvider = scheduleProvider;
        this.completenessRepository = completenessRepository;
        this.snapshotRepository = snapshotRepository;
    }

    @Override
    public boolean supportsPersistedSchedules() {
        return completenessRepository != null && snapshotRepository != null;
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Optional<VersionedCompletionScope> loadPersisted(
            MediaReference reference, String region, String language, Instant now) {
        if (completenessRepository == null || snapshotRepository == null
                || reference == null || reference.type() != ContentType.SEASON
                || reference.seriesTmdbId() == null || reference.seasonNumber() == null
                || reference.seasonNumber() <= 0) {
            return Optional.empty();
        }
        CalendarScheduleCompleteness marker = completenessRepository.findSeasonIdentity(
                reference.seriesTmdbId(), reference.seasonNumber(), region, language).orElse(null);
        List<CalendarScheduleSnapshot> snapshots = snapshotRepository
                .findByEventTypeAndSeriesTmdbIdAndSeasonNumberAndRegionAndLanguage(
                        CalendarScheduleSnapshot.EventType.EPISODE,
                        reference.seriesTmdbId(), reference.seasonNumber(), region, language);
        return persistedSeasonScope(reference, marker, snapshots, region, language, now);
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PersistedSeasonScopes loadPersistedSeasons(
            String seriesTmdbId, java.util.Collection<Integer> seasonNumbers,
            String region, String language, Instant now) {
        if (completenessRepository == null || snapshotRepository == null) {
            return PersistedSeasonScopes.unsupported();
        }
        if (seriesTmdbId == null || seasonNumbers == null || seasonNumbers.isEmpty()) {
            return PersistedSeasonScopes.supported(Map.of());
        }
        List<Integer> seasons = seasonNumbers.stream().filter(Objects::nonNull).filter(number -> number > 0)
                .distinct().sorted().toList();
        if (seasons.isEmpty()) {
            return PersistedSeasonScopes.supported(Map.of());
        }
        Map<Integer, CalendarScheduleCompleteness> markers = completenessRepository
                .findByGroupTypeAndSeriesTmdbIdAndSeasonNumberInAndRegionAndLanguage(
                        CalendarScheduleCompleteness.GroupType.SEASON,
                        seriesTmdbId, seasons, region, language)
                .stream().filter(marker -> marker.getSeasonNumber() != null)
                .collect(Collectors.toMap(CalendarScheduleCompleteness::getSeasonNumber, marker -> marker,
                        (first, ignored) -> first));
        Map<Integer, List<CalendarScheduleSnapshot>> snapshots = snapshotRepository
                .findByEventTypeAndSeriesTmdbIdAndSeasonNumberInAndRegionAndLanguage(
                        CalendarScheduleSnapshot.EventType.EPISODE, seriesTmdbId, seasons, region, language)
                .stream().collect(Collectors.groupingBy(CalendarScheduleSnapshot::getSeasonNumber));
        Map<Integer, VersionedCompletionScope> result = new java.util.LinkedHashMap<>();
        for (Integer season : seasons) {
            persistedSeasonScope(MediaReference.season(seriesTmdbId, season), markers.get(season),
                    snapshots.getOrDefault(season, List.of()), region, language, now)
                    .ifPresent(scope -> result.put(season, scope));
        }
        return PersistedSeasonScopes.supported(result);
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Optional<VersionedCompletionScope> loadPersistedSeries(
            String seriesTmdbId, String region, String language, Instant now) {
        if (completenessRepository == null || snapshotRepository == null
                || seriesTmdbId == null || seriesTmdbId.isBlank()) {
            return Optional.empty();
        }
        CalendarScheduleCompleteness seriesMarker = completenessRepository
                .findSeriesIdentity(seriesTmdbId, region, language).orElse(null);
        List<CalendarScheduleCompleteness> seasonMarkers = completenessRepository
                .findByGroupTypeAndSeriesTmdbIdInAndRegionAndLanguage(
                        CalendarScheduleCompleteness.GroupType.SEASON,
                        List.of(seriesTmdbId), region, language);
        List<CalendarScheduleSnapshot> snapshots = snapshotRepository
                .findByEventTypeAndSeriesTmdbIdAndRegionAndLanguage(
                        CalendarScheduleSnapshot.EventType.EPISODE, seriesTmdbId, region, language);
        Map<Integer, List<CalendarScheduleSnapshot>> bySeason = snapshots.stream()
                .collect(Collectors.groupingBy(CalendarScheduleSnapshot::getSeasonNumber));
        if (seriesMarker == null || !Boolean.TRUE.equals(seriesMarker.getComplete())) {
            Set<EpisodeCoordinate> knownCoordinates = new LinkedHashSet<>();
            Instant validUntil = Instant.MAX;
            for (CalendarScheduleCompleteness marker : seasonMarkers) {
                if (marker.getSeasonNumber() == null || marker.getSeasonNumber() <= 0) {
                    continue;
                }
                Optional<VersionedCompletionScope> season = persistedSeasonScope(
                        MediaReference.season(seriesTmdbId, marker.getSeasonNumber()), marker,
                        bySeason.getOrDefault(marker.getSeasonNumber(), List.of()), region, language, now);
                if (season.isPresent()) {
                    knownCoordinates.addAll(season.get().scope().episodes());
                    validUntil = validUntil.isBefore(season.get().validUntil())
                            ? validUntil : season.get().validUntil();
                }
            }
            if (Instant.MAX.equals(validUntil)) {
                validUntil = CalendarScheduleCadence.nextCheckAt(null, now);
            }
            return Optional.of(new VersionedCompletionScope(
                    new CompletionScope(MediaReference.series(seriesTmdbId), knownCoordinates, false),
                    String.valueOf(seriesMarker == null || seriesMarker.getScopeVersion() == null
                            ? 0L : seriesMarker.getScopeVersion()), validUntil));
        }
        if (seriesMarker.getExpectedEpisodeCount() == null || seriesMarker.getExpectedEpisodeCount() <= 0) {
            return Optional.empty();
        }
        int expectedTotal = 0;
        for (CalendarScheduleCompleteness marker : seasonMarkers) {
            if (!Boolean.TRUE.equals(marker.getComplete()) || marker.getSeasonNumber() == null
                    || marker.getSeasonNumber() <= 0 || marker.getExpectedEpisodeCount() == null
                    || marker.getExpectedEpisodeCount() < 0) {
                return Optional.empty();
            }
            expectedTotal += marker.getExpectedEpisodeCount();
        }
        if (seasonMarkers.isEmpty() || expectedTotal != seriesMarker.getExpectedEpisodeCount()) {
            return Optional.empty();
        }

        LocalDate today = LocalDate.ofInstant(now, ZoneId.systemDefault());
        Set<EpisodeCoordinate> coordinates = new LinkedHashSet<>();
        boolean releaseComplete = true;
        boolean hasLaunchedEpisode = false;
        Instant validUntil = Instant.MAX;
        for (CalendarScheduleCompleteness marker : seasonMarkers) {
            int seasonNumber = marker.getSeasonNumber();
            Optional<VersionedCompletionScope> season = persistedSeasonScope(
                    MediaReference.season(seriesTmdbId, seasonNumber), marker,
                    bySeason.getOrDefault(seasonNumber, List.of()), region, language, now);
            if (season.isEmpty()) {
                return Optional.empty();
            }
            List<CalendarScheduleSnapshot> present = bySeason.getOrDefault(seasonNumber, List.of()).stream()
                    .filter(snapshot -> Boolean.TRUE.equals(snapshot.getPresentInLastTmdbSnapshot())).toList();
            boolean futureOnly = present.stream().allMatch(snapshot -> snapshot.getReleaseDate().isAfter(today));
            validUntil = validUntil.isBefore(season.get().validUntil()) ? validUntil : season.get().validUntil();
            if (futureOnly) {
                continue;
            }
            coordinates.addAll(season.get().scope().episodes());
            boolean launched = present.stream().anyMatch(snapshot -> !snapshot.getReleaseDate().isAfter(today));
            hasLaunchedEpisode |= launched;
            if (present.stream().anyMatch(snapshot -> snapshot.getReleaseDate().isAfter(today))) {
                releaseComplete = false;
            }
        }
        releaseComplete &= hasLaunchedEpisode;
        if (Instant.MAX.equals(validUntil)) {
            validUntil = now.plusSeconds(60);
        }
        return Optional.of(new VersionedCompletionScope(
                new CompletionScope(MediaReference.series(seriesTmdbId), coordinates, releaseComplete),
                String.valueOf(seriesMarker.getScopeVersion() == null ? 0L : seriesMarker.getScopeVersion()),
                validUntil));
    }

    private Optional<VersionedCompletionScope> persistedSeasonScope(
            MediaReference reference,
            CalendarScheduleCompleteness marker,
            List<CalendarScheduleSnapshot> snapshots,
            String region,
            String language,
            Instant now) {
        if (marker == null || !Boolean.TRUE.equals(marker.getComplete())
                || marker.getExpectedEpisodeCount() == null || marker.getExpectedEpisodeCount() <= 0) {
            return Optional.empty();
        }
        List<CalendarScheduleSnapshot> present = snapshots.stream()
                .filter(snapshot -> Boolean.TRUE.equals(snapshot.getPresentInLastTmdbSnapshot()))
                .toList();
        int expectedCount = marker.getExpectedEpisodeCount();
        Set<Integer> episodeNumbers = present.stream()
                .map(CalendarScheduleSnapshot::getEpisodeNumber)
                .filter(number -> number != null && number > 0)
                .collect(Collectors.toSet());
        LocalDate today = LocalDate.ofInstant(now, ZoneId.systemDefault());
        if (present.size() != expectedCount || episodeNumbers.size() != expectedCount
                || present.stream().anyMatch(snapshot -> snapshot.getEpisodeNumber() == null
                        || snapshot.getEpisodeNumber() <= 0 || snapshot.getReleaseDate() == null
                        || snapshot.getNextCheckAt() == null
                        || !snapshot.getNextCheckAt().isAfter(
                                java.time.LocalDateTime.ofInstant(now, java.time.ZoneOffset.UTC)))) {
            return Optional.empty();
        }

        Set<EpisodeCoordinate> coordinates = present.stream()
                .map(snapshot -> new EpisodeCoordinate(reference.seasonNumber(), snapshot.getEpisodeNumber()))
                .collect(Collectors.toCollection(LinkedHashSet::new));
        boolean releaseComplete = present.stream().allMatch(snapshot -> !snapshot.getReleaseDate().isAfter(today));
        Instant validUntil = present.stream()
                .map(snapshot -> snapshot.getNextCheckAt().toInstant(ZoneOffset.UTC))
                .min(Comparator.naturalOrder())
                .orElseGet(() -> now.plusSeconds(60));
        Instant nextRelease = present.stream().map(CalendarScheduleSnapshot::getReleaseDate)
                .filter(date -> date.isAfter(today)).min(Comparator.naturalOrder())
                .map(date -> date.atStartOfDay(ZoneId.systemDefault()).toInstant()).orElse(null);
        if (nextRelease != null && nextRelease.isBefore(validUntil)) {
            validUntil = nextRelease;
        }
        if (!validUntil.isAfter(now)) {
            validUntil = now.plusSeconds(1);
        }
        return Optional.of(new VersionedCompletionScope(
                new CompletionScope(reference, coordinates, releaseComplete),
                String.valueOf(marker.getScopeVersion() == null ? 0L : marker.getScopeVersion()),
                validUntil));
    }

    @Override
    public VersionedCompletionScope load(MediaReference reference, String region, String language, Instant now) {
        Objects.requireNonNull(reference, "reference is required");
        Objects.requireNonNull(now, "now is required");
        if (region == null || region.isBlank() || language == null || language.isBlank()) {
            throw new BadRequestException("Calendar region and language are required");
        }

        return switch (reference.type()) {
            case SERIES -> loadSeries(reference, region, language, now);
            case SEASON -> {
                if (reference.seasonNumber() == null || reference.seasonNumber() <= 0) {
                    throw new BadRequestException("Completion scopes require a regular season");
                }
                yield loadSeason(reference, region, language, now);
            }
            default -> throw new BadRequestException("Completion scopes require a series or season reference");
        };
    }

    @Override
    public VersionedCompletionScope loadPrepared(
            MediaReference reference,
            String region,
            String language,
            Instant now,
            CalendarScheduleBatch preparedSeason) {
        Objects.requireNonNull(reference, "reference is required");
        Objects.requireNonNull(now, "now is required");
        if (reference.type() != com.watchwise.watchwise_api.content.entity.ContentType.SEASON
                || reference.seasonNumber() == null || reference.seasonNumber() <= 0) {
            throw new BadRequestException("Prepared completion scopes require a regular season reference");
        }
        return scopeForSeason(reference, region, language, now,
                requireDirectSeason(new CalendarScheduleLookup.Found(preparedSeason), reference, region, language));
    }

    @Override
    public VersionedCompletionScope loadPreparedSeries(
            MediaReference reference,
            String region,
            String language,
            Instant now,
            CalendarSeriesSchedule preparedSeries) {
        Objects.requireNonNull(reference, "reference is required");
        Objects.requireNonNull(now, "now is required");
        if (reference.type() != com.watchwise.watchwise_api.content.entity.ContentType.SERIES) {
            throw new BadRequestException("Prepared series completion scopes require a series reference");
        }
        return scopeForSeries(reference, region, language, now,
                requireSeriesSchedule(new CalendarScheduleLookup.FoundSeries(preparedSeries),
                        reference.tmdbId(), region, language));
    }

    private VersionedCompletionScope loadSeries(
            MediaReference reference,
            String region,
            String language,
            Instant now) {
        CalendarSeriesSchedule schedule = requireSeriesSchedule(
                scheduleProvider.loadSeries(reference.tmdbId(), region, language),
                reference.tmdbId(), region, language);
        return scopeForSeries(reference, region, language, now, schedule);
    }

    private VersionedCompletionScope scopeForSeries(
            MediaReference reference,
            String region,
            String language,
            Instant now,
            CalendarSeriesSchedule schedule) {
        LocalDate today = LocalDate.ofInstant(now, ZoneId.systemDefault());
        Set<EpisodeCoordinate> episodes = new LinkedHashSet<>();
        boolean releaseComplete = schedule.hasRemoteSummaryAuthority()
                && schedule.seasons().stream()
                .allMatch(season -> season.origin() == com.watchwise.watchwise_api.common.tmdb.TmdbLookupOrigin.REMOTE)
                && isCompleteSeriesCalendar(schedule);
        boolean hasLaunchedRegularEpisode = false;

        for (CalendarSeriesSchedule.Season season : sortedSeasons(schedule.seasons())) {
            if (season.schedule().seasonNumber() == null || season.schedule().seasonNumber() <= 0) {
                continue;
            }
            List<CalendarEpisodeSchedule> seasonEpisodes = season.schedule().episodes();
            if (!season.isFullyRepresented()) {
                releaseComplete = false;
            }
            if (season.expectedEpisodeCount() == 0) {
                if (!seasonEpisodes.isEmpty()) {
                    releaseComplete = false;
                }
                continue;
            }

            boolean datesKnown = seasonEpisodes.stream().allMatch(episode -> episode.releaseDate() != null);
            if (seasonEpisodes.stream().anyMatch(episode -> episode.releaseDate() != null
                    && !episode.releaseDate().isAfter(today))) {
                hasLaunchedRegularEpisode = true;
            }
            boolean unstarted = !seasonEpisodes.isEmpty()
                    && datesKnown
                    && seasonEpisodes.stream().allMatch(episode -> episode.releaseDate().isAfter(today));
            if (unstarted) {
                continue;
            }

            episodes.addAll(toCoordinates(season.schedule()));
            if (!datesKnown || seasonEpisodes.stream().anyMatch(episode -> episode.releaseDate().isAfter(today))) {
                releaseComplete = false;
            }
        }
        releaseComplete = releaseComplete && hasLaunchedRegularEpisode;

        return new VersionedCompletionScope(
                new CompletionScope(reference, episodes, releaseComplete),
                scheduleVersion(region, language, schedule),
                validUntil(schedule.seasons(), today, now));
    }

    private VersionedCompletionScope loadSeason(
            MediaReference reference,
            String region,
            String language,
            Instant now) {
        CalendarScheduleBatch batch = requireDirectSeason(
                scheduleProvider.loadSeason(reference.seriesTmdbId(), reference.seasonNumber(), region, language),
                reference, region, language);
        return scopeForSeason(reference, region, language, now, batch);
    }

    private VersionedCompletionScope scopeForSeason(
            MediaReference reference,
            String region,
            String language,
            Instant now,
            CalendarScheduleBatch batch) {
        CalendarSeasonSchedule season = batch.season();
        Integer expectedEpisodeCount = batch.expectedEpisodeCount();
        LocalDate today = LocalDate.ofInstant(now, ZoneId.systemDefault());
        boolean releaseComplete = batch.origin() == com.watchwise.watchwise_api.common.tmdb.TmdbLookupOrigin.REMOTE
                && seasonReleaseComplete(season, expectedEpisodeCount, today);

        return new VersionedCompletionScope(
                new CompletionScope(reference, toCoordinates(season), releaseComplete),
                scheduleVersion(region, language, season, expectedEpisodeCount),
                seasonValidUntil(season.episodes(), today, now));
    }

    private CalendarSeriesSchedule requireSeriesSchedule(
            CalendarScheduleLookup lookup,
            String seriesTmdbId,
            String region,
            String language) {
        if (lookup instanceof CalendarScheduleLookup.NotFound notFound) {
            if (notFound.seasonNumber() != null) {
                throw new NotFoundException("Season " + notFound.seasonNumber() + " was not found");
            }
            throw new NotFoundException("Series was not found");
        }
        if (lookup instanceof CalendarScheduleLookup.Unavailable) {
            throw new TmdbUnavailableException("Calendar schedule is currently unavailable");
        }
        if (!(lookup instanceof CalendarScheduleLookup.FoundSeries found)) {
            throw new TmdbUnavailableException("Calendar series schedule could not be loaded");
        }

        CalendarSeriesSchedule schedule = found.schedule();
        if (schedule == null) {
            throw new TmdbUnavailableException("Calendar series schedule could not be loaded");
        }
        CalendarScheduleKey key = schedule.key();
        if (!Objects.equals(key.tmdbId(), seriesTmdbId)
                || !Objects.equals(key.preferredRegion(), region)
                || !Objects.equals(key.preferredLanguage(), language)) {
            throw new TmdbUnavailableException("Calendar series schedule did not match the requested reference");
        }
        return schedule;
    }

    private CalendarScheduleBatch requireDirectSeason(
            CalendarScheduleLookup lookup,
            MediaReference reference,
            String region,
            String language) {
        if (lookup instanceof CalendarScheduleLookup.NotFound) {
            throw new NotFoundException("Season " + reference.seasonNumber() + " was not found");
        }
        if (lookup instanceof CalendarScheduleLookup.Unavailable) {
            throw new TmdbUnavailableException("Calendar season is currently unavailable");
        }
        if (!(lookup instanceof CalendarScheduleLookup.Found found)) {
            throw new TmdbUnavailableException("Calendar season schedule could not be loaded");
        }

        CalendarScheduleBatch batch = found.batch();
        if (batch == null) {
            throw new TmdbUnavailableException("Calendar season schedule could not be loaded");
        }
        CalendarSeasonSchedule season = batch.season();
        CalendarScheduleKey key = batch.key();
        if (season == null
                || key.type() != ContentType.SERIES
                || !Objects.equals(key.tmdbId(), reference.seriesTmdbId())
                || !Objects.equals(key.preferredRegion(), region)
                || !Objects.equals(key.preferredLanguage(), language)
                || !Objects.equals(season.seriesTmdbId(), reference.seriesTmdbId())
                || !Objects.equals(season.seasonNumber(), reference.seasonNumber())
                || !Objects.equals(season.region(), region)
                || !Objects.equals(season.language(), language)) {
            throw new TmdbUnavailableException("Calendar season schedule did not match the requested reference");
        }
        return batch;
    }

    private boolean isCompleteSeriesCalendar(CalendarSeriesSchedule schedule) {
        Set<Integer> expectedSeasons = schedule.expectedEpisodeCountsBySeason().keySet().stream()
                .filter(seasonNumber -> seasonNumber != null && seasonNumber > 0)
                .collect(Collectors.toSet());
        Set<Integer> representedSeasons = schedule.seasons().stream()
                .map(season -> season.schedule().seasonNumber())
                .filter(seasonNumber -> seasonNumber != null && seasonNumber > 0)
                .collect(Collectors.toSet());
        return representedSeasons.equals(expectedSeasons)
                && schedule.seasons().stream()
                .filter(season -> season.schedule().seasonNumber() != null && season.schedule().seasonNumber() > 0)
                .allMatch(CalendarSeriesSchedule.Season::isFullyRepresented);
    }

    private boolean seasonReleaseComplete(
            CalendarSeasonSchedule season,
            Integer expectedEpisodeCount,
            LocalDate today) {
        if (season.seasonNumber() == null || season.seasonNumber() <= 0
                || expectedEpisodeCount == null || expectedEpisodeCount <= 0
                || season.episodes().size() != expectedEpisodeCount) {
            return false;
        }
        Set<Integer> episodeNumbers = new HashSet<>();
        for (CalendarEpisodeSchedule episode : season.episodes()) {
            if (episode.episodeNumber() == null || episode.episodeNumber() <= 0
                    || !episodeNumbers.add(episode.episodeNumber())
                    || episode.releaseDate() == null
                    || episode.releaseDate().isAfter(today)) {
                return false;
            }
        }
        return true;
    }

    private Set<EpisodeCoordinate> toCoordinates(CalendarSeasonSchedule season) {
        Set<EpisodeCoordinate> coordinates = new TreeSet<>(Comparator
                .comparingInt(EpisodeCoordinate::seasonNumber)
                .thenComparingInt(EpisodeCoordinate::episodeNumber));
        for (CalendarEpisodeSchedule episode : season.episodes()) {
            if (season.seasonNumber() != null && season.seasonNumber() > 0
                    && episode.episodeNumber() != null && episode.episodeNumber() > 0) {
                coordinates.add(new EpisodeCoordinate(season.seasonNumber(), episode.episodeNumber()));
            }
        }
        return coordinates;
    }

    private List<CalendarSeriesSchedule.Season> sortedSeasons(List<CalendarSeriesSchedule.Season> seasons) {
        return seasons.stream()
                .sorted(Comparator.comparing(season -> season.schedule().seasonNumber()))
                .toList();
    }

    private Instant validUntil(List<CalendarSeriesSchedule.Season> seasons, LocalDate today, Instant now) {
        Instant nextRelease = nextRelease(seasons, today);
        Instant nextCheck = nextScheduleRecheck(seasons, now);
        return nextRelease == null || nextCheck.isBefore(nextRelease) ? nextCheck : nextRelease;
    }

    private Instant seasonValidUntil(List<CalendarEpisodeSchedule> episodes, LocalDate today, Instant now) {
        Instant nextRelease = nextEpisodeRelease(episodes, today);
        Instant nextCheck = nextEpisodeScheduleRecheck(episodes, now);
        return nextRelease == null || nextCheck.isBefore(nextRelease) ? nextCheck : nextRelease;
    }

    private Instant nextRelease(List<CalendarSeriesSchedule.Season> seasons, LocalDate today) {
        return nextEpisodeRelease(seasons.stream()
                .filter(season -> season.schedule().seasonNumber() != null
                        && season.schedule().seasonNumber() > 0)
                .flatMap(season -> season.schedule().episodes().stream())
                .toList(), today);
    }

    private Instant nextEpisodeRelease(List<CalendarEpisodeSchedule> episodes, LocalDate today) {
        return episodes.stream()
                .map(CalendarEpisodeSchedule::releaseDate)
                .filter(Objects::nonNull)
                .filter(date -> date.isAfter(today))
                .min(Comparator.naturalOrder())
                .map(date -> date.atStartOfDay(ZoneId.systemDefault()).toInstant())
                .orElse(null);
    }

    private Instant nextScheduleRecheck(List<CalendarSeriesSchedule.Season> seasons, Instant now) {
        return nextEpisodeScheduleRecheck(seasons.stream()
                .filter(season -> season.schedule().seasonNumber() != null
                        && season.schedule().seasonNumber() > 0)
                .flatMap(season -> season.schedule().episodes().stream())
                .toList(), now);
    }

    private Instant nextEpisodeScheduleRecheck(List<CalendarEpisodeSchedule> episodes, Instant now) {
        Instant nextEpisodeCheck = episodes.stream()
                .map(episode -> CalendarScheduleCadence.nextCheckAt(episode.releaseDate(), now))
                .min(Comparator.naturalOrder())
                .orElse(null);
        if (nextEpisodeCheck != null && !Instant.MAX.equals(nextEpisodeCheck)) {
            return nextEpisodeCheck;
        }

        LocalDate latestKnownRelease = episodes.stream()
                .map(CalendarEpisodeSchedule::releaseDate)
                .filter(Objects::nonNull)
                .max(Comparator.naturalOrder())
                .orElse(null);
        return latestKnownRelease == null
                ? CalendarScheduleCadence.nextCheckAt(null, now)
                : CalendarScheduleCadence.nextSeriesCheckAt(latestKnownRelease, now);
    }

    private String scheduleVersion(
            String region,
            String language,
            CalendarSeriesSchedule schedule) {
        StringBuilder canonical = new StringBuilder()
                .append(schedule.key().tmdbId()).append('\n')
                .append(region).append('\n')
                .append(language).append('\n');
        schedule.expectedEpisodeCountsBySeason().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> canonical.append("expected:")
                        .append(entry.getKey()).append(':').append(entry.getValue()).append('\n'));
        sortedSeasons(schedule.seasons()).forEach(season -> {
            canonical.append("season:")
                    .append(season.schedule().seasonNumber()).append(':')
                    .append(season.expectedEpisodeCount()).append(':')
                    .append(season.isFullyRepresented()).append('\n');
            season.schedule().episodes().stream()
                    .sorted(Comparator.comparing(CalendarEpisodeSchedule::episodeNumber))
                    .forEach(episode -> canonical.append("episode:")
                            .append(episode.episodeNumber()).append(':')
                            .append(episode.releaseDate()).append(':')
                            .append(episode.releaseTime()).append('\n'));
        });
        return sha256(canonical);
    }

    private String scheduleVersion(
            String region,
            String language,
            CalendarSeasonSchedule season,
            Integer expectedEpisodeCount) {
        StringBuilder canonical = new StringBuilder()
                .append(season.seriesTmdbId()).append('\n')
                .append(region).append('\n')
                .append(language).append('\n')
                .append("season:").append(season.seasonNumber()).append('\n')
                .append("expected:").append(expectedEpisodeCount).append('\n')
                .append("episodeCount:").append(season.episodes().size()).append('\n');
        season.episodes().stream()
                .sorted(Comparator.comparing(
                                CalendarEpisodeSchedule::episodeNumber,
                                Comparator.nullsFirst(Integer::compareTo))
                        .thenComparing(CalendarEpisodeSchedule::releaseDate,
                                Comparator.nullsFirst(LocalDate::compareTo))
                        .thenComparing(CalendarEpisodeSchedule::releaseTime,
                                Comparator.nullsFirst(LocalTime::compareTo)))
                .forEach(episode -> canonical.append("episode:")
                        .append(episode.episodeNumber()).append(':')
                        .append(episode.releaseDate()).append(':')
                        .append(episode.releaseTime()).append('\n'));
        return sha256(canonical);
    }

    private String sha256(StringBuilder canonical) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            return "sha256:" + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
