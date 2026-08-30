package com.watchwise.watchwise_api.notification.tracking;

import com.watchwise.watchwise_api.common.tmdb.TmdbMovieDetails;
import com.watchwise.watchwise_api.common.tmdb.TmdbTvDetails;
import com.watchwise.watchwise_api.notification.entity.NotificationType;
import com.watchwise.watchwise_api.notification.entity.TrackedContentState;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Component
public class ContentChangeDetector {

    private static final String CANCELED_STATUS = "Canceled";

    public Optional<ContentChangeEvent> detectMovieChange(TrackedContentState previous, TmdbMovieDetails fresh, LocalDate today) {
        LocalDate freshReleaseDate = parseDate(fresh.releaseDate());
        LocalDate previousReleaseDate = previous == null ? null : previous.getLastKnownReleaseDate();
        String previousStatus = previous == null ? null : previous.getLastKnownStatus();

        if (!CANCELED_STATUS.equals(previousStatus) && CANCELED_STATUS.equals(fresh.status())) {
            return Optional.of(new ContentChangeEvent(NotificationType.CANCELLED, null, null, null));
        }

        if (previousReleaseDate != null && !today.isBefore(previousReleaseDate)) {
            return Optional.of(new ContentChangeEvent(NotificationType.RELEASE, previousReleaseDate, null, null));
        }

        if (previousReleaseDate == null && freshReleaseDate != null && freshReleaseDate.isAfter(today)) {
            return Optional.of(new ContentChangeEvent(NotificationType.ANNOUNCED_DATE, freshReleaseDate, null, null));
        }

        return Optional.empty();
    }

    public List<ContentChangeEvent> detectTvChange(TrackedContentState previous, TmdbTvDetails fresh, LocalDate today) {
        List<ContentChangeEvent> events = new ArrayList<>();
        String previousStatus = previous == null ? null : previous.getLastKnownStatus();

        if (!CANCELED_STATUS.equals(previousStatus) && CANCELED_STATUS.equals(fresh.status())) {
            events.add(new ContentChangeEvent(NotificationType.CANCELLED, null, null, null));
        } else if (isEndedOrCancelled(previousStatus) && "Returning Series".equals(fresh.status())) {
            events.add(new ContentChangeEvent(NotificationType.RENEWED, null, null, null));
        }

        if (previous != null && previous.getNextEpisodeAirDate() != null
                && !today.isBefore(previous.getNextEpisodeAirDate())) {
            events.add(new ContentChangeEvent(NotificationType.NEW_EPISODE, previous.getNextEpisodeAirDate(),
                    previous.getNextEpisodeSeasonNumber(), previous.getNextEpisodeNumber()));
        }

        return events;
    }

    private boolean isEndedOrCancelled(String status) {
        return "Ended".equals(status) || CANCELED_STATUS.equals(status);
    }

    private LocalDate parseDate(String value) {
        return StringUtils.hasText(value) ? LocalDate.parse(value) : null;
    }
}
