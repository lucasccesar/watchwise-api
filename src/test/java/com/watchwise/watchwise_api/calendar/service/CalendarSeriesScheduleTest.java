package com.watchwise.watchwise_api.calendar.service;

import com.watchwise.watchwise_api.common.tmdb.TmdbLookupOrigin;
import com.watchwise.watchwise_api.content.entity.ContentType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CalendarSeriesScheduleTest {

    @Test
    void shouldRejectDuplicateEpisodeCoordinatesBeforeCompletenessCanBeCalculated() {
        CalendarSeasonSchedule malformed = new CalendarSeasonSchedule("1396", 1, "BR", "pt-BR", "Series", null,
                List.of(
                        new CalendarEpisodeSchedule(1, "One", null, null, null, null),
                        new CalendarEpisodeSchedule(1, "Duplicate", null, null, null, null)));

        assertThatThrownBy(() -> new CalendarSeriesSchedule(
                new CalendarScheduleKey(ContentType.SERIES, "1396", "pt-BR", "BR"),
                List.of(new CalendarSeriesSchedule.Season(malformed, 2, TmdbLookupOrigin.REMOTE)),
                Map.of(1, 2),
                2)).isInstanceOf(IllegalArgumentException.class);
    }
}
