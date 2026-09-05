package com.watchwise.watchwise_api.notification.tracking;

import com.watchwise.watchwise_api.common.tmdb.TmdbMovieDetails;
import com.watchwise.watchwise_api.common.tmdb.TmdbSeasonSummary;
import com.watchwise.watchwise_api.common.tmdb.TmdbTvDetails;
import com.watchwise.watchwise_api.notification.entity.NotificationType;
import com.watchwise.watchwise_api.notification.entity.TrackedContentState;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

@Component
public class ContentChangeDetector {

    public static final String CANCELED_STATUS = "Canceled";
    public static final String RELEASED_STATUS = "Released";
    public static final String ENDED_STATUS = "Ended";
    public static final String RETURNING_SERIES_STATUS = "Returning Series";

    private static final List<String> PRE_RELEASE_STATUSES = List.of("Planned", "In Production", "Pilot");

    public Optional<ContentChangeEvent> detectMovieChange(TrackedContentState previous, TmdbMovieDetails fresh, LocalDate today) {
        if (previous == null) {
            return Optional.empty();
        }

        LocalDate previousReleaseDate = previous.getLastKnownReleaseDate();
        String previousStatus = previous.getLastKnownStatus();

        if (!CANCELED_STATUS.equals(previousStatus) && CANCELED_STATUS.equals(fresh.status())) {
            return Optional.of(new ContentChangeEvent(NotificationType.CANCELLED, null, null, null));
        }

        if (previousStatus != null && !RELEASED_STATUS.equals(previousStatus) && RELEASED_STATUS.equals(fresh.status())) {
            return Optional.of(new ContentChangeEvent(NotificationType.RELEASE, previousReleaseDate, null, null));
        }

        LocalDate freshReleaseDate = TmdbDateParser.parseDate(fresh.releaseDate());
        if (previousReleaseDate == null && freshReleaseDate != null && freshReleaseDate.isAfter(today)) {
            return Optional.of(new ContentChangeEvent(NotificationType.ANNOUNCED_DATE, freshReleaseDate, null, null));
        }

        return Optional.empty();
    }

    public List<ContentChangeEvent> detectTvChange(TrackedContentState previous, TmdbTvDetails fresh, LocalDate today) {
        if (previous == null) {
            return List.of();
        }

        List<ContentChangeEvent> events = new ArrayList<>();
        String previousStatus = previous.getLastKnownStatus();

        if (!CANCELED_STATUS.equals(previousStatus) && CANCELED_STATUS.equals(fresh.status())) {
            events.add(new ContentChangeEvent(NotificationType.CANCELLED, null, null, null));
        } else if (isEndedOrCancelled(previousStatus) && RETURNING_SERIES_STATUS.equals(fresh.status())) {
            events.add(new ContentChangeEvent(NotificationType.RENEWED, null, null, null));
        } else if (isPreRelease(previousStatus)
                && (RETURNING_SERIES_STATUS.equals(fresh.status()) || ENDED_STATUS.equals(fresh.status()))) {
            events.add(new ContentChangeEvent(NotificationType.RELEASE, null, null, null));
        }

        if (previous.getNextEpisodeAirDate() != null && !today.isBefore(previous.getNextEpisodeAirDate())) {
            events.add(new ContentChangeEvent(NotificationType.NEW_EPISODE, previous.getNextEpisodeAirDate(),
                    previous.getNextEpisodeSeasonNumber(), previous.getNextEpisodeNumber()));
        }

        TmdbSeasonSummary freshLatestSeason = latestSeason(fresh.seasons());
        if (freshLatestSeason != null) {
            LocalDate freshSeasonAirDate = TmdbDateParser.parseDate(freshLatestSeason.airDate());
            LocalDate previousSeasonAirDate = freshLatestSeason.seasonNumber().equals(previous.getLastKnownSeasonNumber())
                    ? previous.getLastKnownSeasonAirDate()
                    : null;
            if (previousSeasonAirDate == null && freshSeasonAirDate != null && freshSeasonAirDate.isAfter(today)) {
                events.add(new ContentChangeEvent(NotificationType.ANNOUNCED_DATE, freshSeasonAirDate,
                        freshLatestSeason.seasonNumber(), null));
            }
        }

        return events;
    }

    public static TmdbSeasonSummary latestSeason(List<TmdbSeasonSummary> seasons) {
        if (seasons == null) {
            return null;
        }
        return seasons.stream()
                .filter(season -> season.seasonNumber() != null && season.seasonNumber() != 0)
                .max(Comparator.comparing(TmdbSeasonSummary::seasonNumber))
                .orElse(null);
    }

    private boolean isEndedOrCancelled(String status) {
        return ENDED_STATUS.equals(status) || CANCELED_STATUS.equals(status);
    }

    private boolean isPreRelease(String status) {
        return PRE_RELEASE_STATUSES.contains(status);
    }
}
