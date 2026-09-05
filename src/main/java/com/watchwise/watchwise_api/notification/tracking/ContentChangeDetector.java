package com.watchwise.watchwise_api.notification.tracking;

import com.watchwise.watchwise_api.common.tmdb.TmdbMovieDetails;
import com.watchwise.watchwise_api.common.tmdb.TmdbTvDetails;
import com.watchwise.watchwise_api.notification.entity.NotificationType;
import com.watchwise.watchwise_api.notification.entity.TrackedContentState;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Component
public class ContentChangeDetector {

    public static final String CANCELED_STATUS = "Canceled";
    public static final String RELEASED_STATUS = "Released";
    public static final String ENDED_STATUS = "Ended";

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
        } else if (isEndedOrCancelled(previousStatus) && "Returning Series".equals(fresh.status())) {
            events.add(new ContentChangeEvent(NotificationType.RENEWED, null, null, null));
        }

        if (previous.getNextEpisodeAirDate() != null && !today.isBefore(previous.getNextEpisodeAirDate())) {
            events.add(new ContentChangeEvent(NotificationType.NEW_EPISODE, previous.getNextEpisodeAirDate(),
                    previous.getNextEpisodeSeasonNumber(), previous.getNextEpisodeNumber()));
        }

        return events;
    }

    private boolean isEndedOrCancelled(String status) {
        return ENDED_STATUS.equals(status) || CANCELED_STATUS.equals(status);
    }
}
