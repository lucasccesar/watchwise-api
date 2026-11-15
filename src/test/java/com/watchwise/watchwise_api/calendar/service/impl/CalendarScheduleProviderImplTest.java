package com.watchwise.watchwise_api.calendar.service.impl;

import com.watchwise.watchwise_api.calendar.service.CalendarScheduleLookup;
import com.watchwise.watchwise_api.common.tmdb.TmdbLookupOrigin;
import com.watchwise.watchwise_api.content.service.ContentSchedule;
import com.watchwise.watchwise_api.content.service.ContentScheduleEpisode;
import com.watchwise.watchwise_api.content.service.ContentScheduleKey;
import com.watchwise.watchwise_api.content.service.ContentScheduleLookup;
import com.watchwise.watchwise_api.content.service.ContentScheduleReader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CalendarScheduleProviderImplTest {

    @Mock
    private ContentScheduleReader scheduleReader;

    @Test
    void shouldNotAcceptFreshSeasonDetailsAgainstACachedSeriesSummary() {
        CalendarScheduleProviderImpl provider = new CalendarScheduleProviderImpl(scheduleReader);
        ContentSchedule schedule = seasonSchedule();
        when(scheduleReader.readCalendarSeasonFresh("1396", 1, "BR", "pt-BR"))
                .thenReturn(new ContentScheduleLookup.Found(
                        schedule,
                        TmdbLookupOrigin.REMOTE,
                        Map.of(1, TmdbLookupOrigin.REMOTE),
                        TmdbLookupOrigin.CACHE));

        CalendarScheduleLookup result = provider.loadSeasonFresh("1396", 1, "BR", "pt-BR");

        assertThat(result).isInstanceOfSatisfying(CalendarScheduleLookup.Found.class, found -> {
            assertThat(found.batch().origin()).isEqualTo(TmdbLookupOrigin.CACHE);
            assertThat(found.batch().expectedEpisodeCount()).isEqualTo(1);
        });
    }

    @Test
    void shouldNotAcceptRemoteSeasonPayloadsWhenTheSeriesSummaryIsCached() {
        CalendarScheduleProviderImpl provider = new CalendarScheduleProviderImpl(scheduleReader);
        ContentSchedule schedule = new ContentSchedule(
                ContentScheduleKey.series("1396"),
                null,
                "Returning Series",
                List.of(new ContentScheduleEpisode("1396", 1, 1, LocalDate.of(2026, 1, 1))),
                true,
                false,
                "Breaking Bad",
                null,
                Map.of(1, 1));
        when(scheduleReader.readCalendarSeriesFresh("1396", "BR", "pt-BR"))
                .thenReturn(new ContentScheduleLookup.Found(
                        schedule,
                        TmdbLookupOrigin.CACHE,
                        Map.of(1, TmdbLookupOrigin.REMOTE),
                        TmdbLookupOrigin.CACHE));

        CalendarScheduleLookup result = provider.loadSeriesFresh("1396", "BR", "pt-BR");

        assertThat(result).isInstanceOfSatisfying(CalendarScheduleLookup.FoundSeries.class, found -> {
            assertThat(found.schedule().completeSchedule()).isFalse();
            assertThat(found.schedule().hasRemoteSeasonPayloads()).isFalse();
            assertThat(found.schedule().hasRemoteResults()).isFalse();
            assertThat(found.schedule().seasons()).singleElement()
                    .extracting(season -> season.origin())
                    .isEqualTo(TmdbLookupOrigin.CACHE);
        });
    }

    private ContentSchedule seasonSchedule() {
        return new ContentSchedule(
                ContentScheduleKey.season("1396", 1),
                LocalDate.of(2008, 1, 20),
                "Ended",
                List.of(new ContentScheduleEpisode("1396", 1, 1, LocalDate.of(2008, 1, 20))),
                true,
                false,
                "Season 1",
                null,
                Map.of(1, 1));
    }
}
