package com.watchwise.watchwise_api.diarycompletion.service.impl;

import com.watchwise.watchwise_api.calendar.service.CalendarEpisodeSchedule;
import com.watchwise.watchwise_api.calendar.service.CalendarScheduleBatch;
import com.watchwise.watchwise_api.calendar.service.CalendarScheduleCadence;
import com.watchwise.watchwise_api.calendar.service.CalendarScheduleKey;
import com.watchwise.watchwise_api.calendar.service.CalendarScheduleLookup;
import com.watchwise.watchwise_api.calendar.service.CalendarScheduleProvider;
import com.watchwise.watchwise_api.calendar.service.CalendarSeasonSchedule;
import com.watchwise.watchwise_api.calendar.service.CalendarSeriesSchedule;
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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

@Service
public class CalendarCompletionScopeLoader implements CompletionScopeLoader {

    private final CalendarScheduleProvider scheduleProvider;

    public CalendarCompletionScopeLoader(CalendarScheduleProvider scheduleProvider) {
        this.scheduleProvider = scheduleProvider;
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
            case SEASON -> loadSeason(reference, region, language, now);
            default -> throw new BadRequestException("Completion scopes require a series or season reference");
        };
    }

    private VersionedCompletionScope loadSeries(
            MediaReference reference,
            String region,
            String language,
            Instant now) {
        CalendarSeriesSchedule schedule = requireSeriesSchedule(
                scheduleProvider.loadSeries(reference.tmdbId(), region, language),
                reference.tmdbId(), region, language);
        LocalDate today = LocalDate.ofInstant(now, ZoneId.systemDefault());
        Set<EpisodeCoordinate> episodes = new LinkedHashSet<>();
        boolean releaseComplete = isCompleteSeriesCalendar(schedule);
        boolean hasLaunchedRegularEpisode = false;

        for (CalendarSeriesSchedule.Season season : sortedSeasons(schedule.seasons())) {
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
        CalendarSeasonSchedule season = batch.season();
        Integer expectedEpisodeCount = batch.expectedEpisodeCount();
        LocalDate today = LocalDate.ofInstant(now, ZoneId.systemDefault());
        boolean releaseComplete = seasonReleaseComplete(season, expectedEpisodeCount, today);

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
        Set<Integer> expectedSeasons = schedule.expectedEpisodeCountsBySeason().keySet();
        Set<Integer> representedSeasons = schedule.seasons().stream()
                .map(season -> season.schedule().seasonNumber())
                .collect(Collectors.toSet());
        return representedSeasons.equals(expectedSeasons)
                && schedule.seasons().stream().allMatch(CalendarSeriesSchedule.Season::isFullyRepresented);
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
