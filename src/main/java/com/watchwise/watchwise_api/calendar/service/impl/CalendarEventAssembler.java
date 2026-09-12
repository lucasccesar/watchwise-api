package com.watchwise.watchwise_api.calendar.service.impl;

import com.watchwise.watchwise_api.calendar.dto.CalendarEventContentDTO;
import com.watchwise.watchwise_api.calendar.dto.CalendarEventDTO;
import com.watchwise.watchwise_api.calendar.dto.CalendarEventType;
import com.watchwise.watchwise_api.calendar.dto.CalendarSource;
import com.watchwise.watchwise_api.calendar.dto.EpisodeCalendarContentDTO;
import com.watchwise.watchwise_api.calendar.dto.MovieCalendarContentDTO;
import com.watchwise.watchwise_api.calendar.dto.ReleaseStatus;
import com.watchwise.watchwise_api.calendar.dto.SeasonCalendarContentDTO;
import com.watchwise.watchwise_api.calendar.dto.SeriesCalendarContentDTO;
import com.watchwise.watchwise_api.calendar.dto.WatchStatus;
import com.watchwise.watchwise_api.calendar.entity.CalendarScheduleSnapshot;
import com.watchwise.watchwise_api.calendar.service.CalendarAssemblyInput;
import com.watchwise.watchwise_api.calendar.service.CalendarAssemblyInput.CompleteSeasonKey;
import com.watchwise.watchwise_api.calendar.service.CalendarScheduleKey;
import com.watchwise.watchwise_api.calendar.service.WatchedCalendarKey;
import com.watchwise.watchwise_api.content.entity.ContentType;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Pure transformation from complete schedule snapshots to a single month's calendar events.
 */
public class CalendarEventAssembler {

    private static final Comparator<AssembledEvent> EVENT_ORDER = Comparator
            .comparing(AssembledEvent::date)
            .thenComparingInt(event -> eventTypeRank(event.eventType()))
            .thenComparing(AssembledEvent::externalId)
            .thenComparingInt(AssembledEvent::seasonNumber)
            .thenComparingInt(AssembledEvent::episodeNumber);

    public List<CalendarEventDTO> assemble(CalendarAssemblyInput input) {
        Objects.requireNonNull(input, "input is required");

        List<CalendarScheduleSnapshot> validSnapshots = input.snapshots().stream()
                .filter(this::hasValidCoordinates)
                .filter(snapshot -> hasInputLocale(snapshot, input))
                .toList();
        List<AssembledEvent> events = new ArrayList<>();

        addMovieEvents(validSnapshots, input, events);
        addEpisodeEvents(validSnapshots, input, events);

        return events.stream()
                .filter(event -> input.month().equals(java.time.YearMonth.from(event.date())))
                .sorted(EVENT_ORDER)
                .map(AssembledEvent::event)
                .toList();
    }

    private void addMovieEvents(
            List<CalendarScheduleSnapshot> snapshots,
            CalendarAssemblyInput input,
            List<AssembledEvent> events) {
        snapshots.stream()
                .filter(snapshot -> snapshot.getEventType() == CalendarScheduleSnapshot.EventType.MOVIE)
                .filter(snapshot -> snapshot.getReleaseDate() != null)
                .forEach(snapshot -> {
                    String tmdbId = snapshot.getTmdbId();
                    boolean watched = input.watchedKeys().contains(WatchedCalendarKey.movie(tmdbId));
                    events.add(event(
                            snapshot.getReleaseDate(),
                            CalendarEventType.MOVIE,
                            releaseStatus(snapshot.getReleaseDate(), input),
                            watched ? WatchStatus.WATCHED : WatchStatus.UNWATCHED,
                            sourcesForMovie(tmdbId, input),
                            new MovieCalendarContentDTO(tmdbId, snapshot.getTitle(), snapshot.getPosterPath()),
                            tmdbId,
                            0,
                            0));
                });
    }

    private void addEpisodeEvents(
            List<CalendarScheduleSnapshot> snapshots,
            CalendarAssemblyInput input,
            List<AssembledEvent> events) {
        Map<String, List<CalendarScheduleSnapshot>> episodesBySeries = snapshots.stream()
                .filter(snapshot -> snapshot.getEventType() == CalendarScheduleSnapshot.EventType.EPISODE)
                .collect(Collectors.groupingBy(CalendarScheduleSnapshot::getSeriesTmdbId));

        episodesBySeries.values().forEach(seriesEpisodes -> addSeriesEvents(seriesEpisodes, input, events));
    }

    private void addSeriesEvents(
            List<CalendarScheduleSnapshot> seriesEpisodes,
            CalendarAssemblyInput input,
            List<AssembledEvent> events) {
        if (isCompleteSeriesWithOneDate(seriesEpisodes, input)) {
            CalendarScheduleSnapshot representative = representative(seriesEpisodes);
            events.add(event(
                    representative.getReleaseDate(),
                    CalendarEventType.SERIES,
                    releaseStatus(representative.getReleaseDate(), input),
                    groupedWatchStatus(seriesEpisodes, input.watchedKeys()),
                    sourcesForSeries(representative.getSeriesTmdbId(), input),
                    new SeriesCalendarContentDTO(
                            representative.getSeriesTmdbId(),
                            seriesEpisodes.size(),
                            representative.getSeriesTitle(),
                            representative.getPosterPath()),
                    representative.getSeriesTmdbId(),
                    0,
                    0));
            return;
        }

        Map<Integer, List<CalendarScheduleSnapshot>> episodesBySeason = seriesEpisodes.stream()
                .collect(Collectors.groupingBy(CalendarScheduleSnapshot::getSeasonNumber));
        episodesBySeason.values().forEach(seasonEpisodes -> addSeasonEvents(seasonEpisodes, input, events));
    }

    private void addSeasonEvents(
            List<CalendarScheduleSnapshot> seasonEpisodes,
            CalendarAssemblyInput input,
            List<AssembledEvent> events) {
        if (isCompleteSeasonWithOneDate(seasonEpisodes, input)) {
            CalendarScheduleSnapshot representative = representative(seasonEpisodes);
            events.add(event(
                    representative.getReleaseDate(),
                    CalendarEventType.SEASON,
                    releaseStatus(representative.getReleaseDate(), input),
                    groupedWatchStatus(seasonEpisodes, input.watchedKeys()),
                    sourcesForSeries(representative.getSeriesTmdbId(), input),
                    new SeasonCalendarContentDTO(
                            representative.getSeriesTmdbId(),
                            representative.getSeasonNumber(),
                            seasonEpisodes.size(),
                            representative.getSeriesTitle(),
                            representative.getPosterPath()),
                    representative.getSeriesTmdbId(),
                    representative.getSeasonNumber(),
                    0));
            return;
        }

        seasonEpisodes.stream()
                .filter(snapshot -> snapshot.getReleaseDate() != null)
                .forEach(snapshot -> events.add(episodeEvent(snapshot, input)));
    }

    private AssembledEvent episodeEvent(CalendarScheduleSnapshot snapshot, CalendarAssemblyInput input) {
        boolean watched = input.watchedKeys().contains(WatchedCalendarKey.episode(
                snapshot.getSeriesTmdbId(), snapshot.getSeasonNumber(), snapshot.getEpisodeNumber()));
        return event(
                snapshot.getReleaseDate(),
                CalendarEventType.EPISODE,
                releaseStatus(snapshot.getReleaseDate(), input),
                watched ? WatchStatus.WATCHED : WatchStatus.UNWATCHED,
                sourcesForSeries(snapshot.getSeriesTmdbId(), input),
                new EpisodeCalendarContentDTO(
                        snapshot.getSeriesTmdbId(),
                        snapshot.getSeasonNumber(),
                        snapshot.getEpisodeNumber(),
                        snapshot.getTitle(),
                        snapshot.getSeriesTitle(),
                        snapshot.getPosterPath(),
                        snapshot.getStillPath()),
                snapshot.getSeriesTmdbId(),
                snapshot.getSeasonNumber(),
                snapshot.getEpisodeNumber());
    }

    private boolean hasValidCoordinates(CalendarScheduleSnapshot snapshot) {
        if (snapshot == null
                || !Boolean.TRUE.equals(snapshot.getPresentInLastTmdbSnapshot())) {
            return false;
        }
        if (snapshot.getEventType() == CalendarScheduleSnapshot.EventType.MOVIE) {
            return !isBlank(snapshot.getTmdbId());
        }
        return snapshot.getEventType() == CalendarScheduleSnapshot.EventType.EPISODE
                && !isBlank(snapshot.getSeriesTmdbId())
                && snapshot.getSeasonNumber() != null
                && snapshot.getSeasonNumber() > 0
                && snapshot.getEpisodeNumber() != null
                && snapshot.getEpisodeNumber() > 0;
    }

    private boolean hasInputLocale(CalendarScheduleSnapshot snapshot, CalendarAssemblyInput input) {
        return input.region().equals(snapshot.getRegion())
                && input.preferredLanguage().equals(snapshot.getLanguage());
    }

    private boolean isCompleteSeasonWithOneDate(
            List<CalendarScheduleSnapshot> episodes, CalendarAssemblyInput input) {
        CalendarScheduleSnapshot representative = representative(episodes);
        return input.completeness().completeSeasonKeys().contains(
                new CompleteSeasonKey(representative.getSeriesTmdbId(), representative.getSeasonNumber()))
                && hasOneReleaseDate(episodes);
    }

    private boolean isCompleteSeriesWithOneDate(
            List<CalendarScheduleSnapshot> episodes, CalendarAssemblyInput input) {
        CalendarScheduleSnapshot representative = representative(episodes);
        return input.completeness().completeSeriesKeys().contains(new CalendarScheduleKey(
                ContentType.SERIES,
                representative.getSeriesTmdbId(),
                input.preferredLanguage(),
                input.region()))
                && hasOneReleaseDate(episodes);
    }

    private boolean hasOneReleaseDate(List<CalendarScheduleSnapshot> episodes) {
        return !episodes.isEmpty()
                && episodes.stream().map(CalendarScheduleSnapshot::getReleaseDate).noneMatch(Objects::isNull)
                && episodes.stream()
                        .map(CalendarScheduleSnapshot::getReleaseDate)
                        .distinct()
                        .limit(2)
                        .count() == 1;
    }

    private CalendarScheduleSnapshot representative(List<CalendarScheduleSnapshot> snapshots) {
        return snapshots.stream()
                .min(Comparator.comparing(CalendarScheduleSnapshot::getSeasonNumber)
                        .thenComparing(CalendarScheduleSnapshot::getEpisodeNumber))
                .orElseThrow();
    }

    private WatchStatus groupedWatchStatus(
            List<CalendarScheduleSnapshot> episodes, Set<WatchedCalendarKey> watchedKeys) {
        long watchedCount = episodes.stream()
                .filter(episode -> watchedKeys.contains(WatchedCalendarKey.episode(
                        episode.getSeriesTmdbId(), episode.getSeasonNumber(), episode.getEpisodeNumber())))
                .count();
        if (watchedCount == 0) {
            return WatchStatus.UNWATCHED;
        }
        return watchedCount == episodes.size() ? WatchStatus.WATCHED : WatchStatus.PARTIALLY_WATCHED;
    }

    private ReleaseStatus releaseStatus(LocalDate releaseDate, CalendarAssemblyInput input) {
        return releaseDate.isAfter(LocalDate.now(input.clock())) ? ReleaseStatus.UPCOMING : ReleaseStatus.RELEASED;
    }

    private Set<CalendarSource> sourcesForMovie(String tmdbId, CalendarAssemblyInput input) {
        return mergeSources(input.sourcesByKey(),
                key -> key.type() == ContentType.MOVIE && tmdbId.equals(key.tmdbId()) && hasInputLocale(key, input));
    }

    private Set<CalendarSource> sourcesForSeries(String seriesTmdbId, CalendarAssemblyInput input) {
        return mergeSources(input.sourcesByKey(),
                key -> key.type() == ContentType.SERIES && seriesTmdbId.equals(key.tmdbId()) && hasInputLocale(key, input));
    }

    private boolean hasInputLocale(CalendarScheduleKey key, CalendarAssemblyInput input) {
        return input.region().equals(key.preferredRegion())
                && input.preferredLanguage().equals(key.preferredLanguage());
    }

    private Set<CalendarSource> mergeSources(
            Map<CalendarScheduleKey, Set<CalendarSource>> sourcesByKey,
            java.util.function.Predicate<CalendarScheduleKey> matchingKey) {
        EnumSet<CalendarSource> mergedSources = EnumSet.noneOf(CalendarSource.class);
        sourcesByKey.forEach((key, sources) -> {
            if (matchingKey.test(key)) {
                mergedSources.addAll(sources);
            }
        });
        return Set.copyOf(mergedSources);
    }

    private AssembledEvent event(
            LocalDate date,
            CalendarEventType eventType,
            ReleaseStatus releaseStatus,
            WatchStatus watchStatus,
            Set<CalendarSource> sources,
            CalendarEventContentDTO content,
            String externalId,
            int seasonNumber,
            int episodeNumber) {
        return new AssembledEvent(
                date,
                eventType,
                externalId,
                seasonNumber,
                episodeNumber,
                new CalendarEventDTO(date, eventType, releaseStatus, watchStatus, sources, content));
    }

    private static int eventTypeRank(CalendarEventType eventType) {
        return switch (eventType) {
            case MOVIE -> 0;
            case SERIES -> 1;
            case SEASON -> 2;
            case EPISODE -> 3;
        };
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private record AssembledEvent(
            LocalDate date,
            CalendarEventType eventType,
            String externalId,
            int seasonNumber,
            int episodeNumber,
            CalendarEventDTO event) {
    }
}
