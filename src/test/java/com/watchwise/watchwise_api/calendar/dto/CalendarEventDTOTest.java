package com.watchwise.watchwise_api.calendar.dto;

import com.watchwise.watchwise_api.content.dto.ReleaseStatus;
import com.watchwise.watchwise_api.content.dto.WatchStatus;
import com.watchwise.watchwise_api.calendar.service.CalendarService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.lang.reflect.Method;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class CalendarEventDTOTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("Should Expose Every Calendar Payload Shape")
    void shouldExposeEveryCalendarPayloadShape() {
        MovieCalendarContentDTO movie = new MovieCalendarContentDTO("550", "Fight Club", "/fight-club.jpg");
        EpisodeCalendarContentDTO episode = new EpisodeCalendarContentDTO(
                "1396", 2, 3, "Four Days Out", "Breaking Bad", "/breaking-bad.jpg", "/four-days-out.jpg");
        SeasonCalendarContentDTO season = new SeasonCalendarContentDTO(
                "1396", 2, 13, "Season 2", "/season-2.jpg");
        SeriesCalendarContentDTO series = new SeriesCalendarContentDTO(
                "1396", 5, "Breaking Bad", "/breaking-bad.jpg");

        assertThat(CalendarEventContentDTO.class.isSealed()).isTrue();
        assertThat(CalendarEventContentDTO.class.getPermittedSubclasses())
                .containsExactlyInAnyOrder(
                        MovieCalendarContentDTO.class,
                        EpisodeCalendarContentDTO.class,
                        SeasonCalendarContentDTO.class,
                        SeriesCalendarContentDTO.class);
        assertThat(movie).isEqualTo(new MovieCalendarContentDTO("550", "Fight Club", "/fight-club.jpg"));
        assertThat(episode).isEqualTo(new EpisodeCalendarContentDTO(
                "1396", 2, 3, "Four Days Out", "Breaking Bad", "/breaking-bad.jpg", "/four-days-out.jpg"));
        assertThat(season).isEqualTo(new SeasonCalendarContentDTO(
                "1396", 2, 13, "Season 2", "/season-2.jpg"));
        assertThat(series).isEqualTo(new SeriesCalendarContentDTO(
                "1396", 5, "Breaking Bad", "/breaking-bad.jpg"));
    }

    @Test
    @DisplayName("Should Preserve Nullable Image Paths")
    void shouldPreserveNullableImagePaths() throws Exception {
        CalendarEventDTO event = new CalendarEventDTO(
                LocalDate.of(2026, 9, 12),
                CalendarEventType.EPISODE,
                ReleaseStatus.UPCOMING,
                WatchStatus.UNWATCHED,
                Set.of(CalendarSource.IN_PROGRESS),
                new EpisodeCalendarContentDTO("1396", 2, 3, "Four Days Out", "Breaking Bad", null, null));

        JsonNode content = objectMapper.readTree(objectMapper.writeValueAsString(event)).get("content");

        assertThat(content.get("posterPath").isNull()).isTrue();
        assertThat(content.get("stillPath").isNull()).isTrue();
    }

    @Test
    @DisplayName("Should Represent Partially Watched Events")
    void shouldRepresentPartiallyWatchedEvents() {
        CalendarEventDTO event = new CalendarEventDTO(
                LocalDate.of(2026, 9, 12),
                CalendarEventType.SEASON,
                ReleaseStatus.RELEASED,
                WatchStatus.PARTIALLY_WATCHED,
                Set.of(CalendarSource.IN_PROGRESS, CalendarSource.WATCHLIST),
                new SeasonCalendarContentDTO("1396", 2, 13, "Season 2", "/season-2.jpg"));

        assertThat(event.watchStatus()).isEqualTo(WatchStatus.PARTIALLY_WATCHED);
        assertThat(event.sources()).containsExactlyInAnyOrder(CalendarSource.IN_PROGRESS, CalendarSource.WATCHLIST);
    }

    @Test
    @DisplayName("Should Serialize A Calendar Response")
    void shouldSerializeACalendarResponse() throws Exception {
        CalendarEventDTO event = new CalendarEventDTO(
                LocalDate.of(2026, 9, 12),
                CalendarEventType.MOVIE,
                ReleaseStatus.RELEASED,
                WatchStatus.UNWATCHED,
                Set.of(CalendarSource.WATCHLIST),
                new MovieCalendarContentDTO("603", "The Matrix", null));
        CalendarResponseDTO response = new CalendarResponseDTO(YearMonth.of(2026, 9), "BR", List.of(event));

        JsonNode json = objectMapper.readTree(objectMapper.writeValueAsString(response));

        assertThat(json.get("month").asText()).isEqualTo("2026-09");
        assertThat(json.get("region").asText()).isEqualTo("BR");
        assertThat(json.get("events")).hasSize(1);
        assertThat(json.get("events").get(0).get("date").asText()).isEqualTo("2026-09-12");
        assertThat(json.get("events").get(0).get("eventType").asText()).isEqualTo("MOVIE");
        assertThat(json.get("events").get(0).get("releaseStatus").asText()).isEqualTo("RELEASED");
        assertThat(json.get("events").get(0).get("watchStatus").asText()).isEqualTo("UNWATCHED");
        assertThat(json.get("events").get(0).get("content").get("tmdbId").asText()).isEqualTo("603");
        assertThat(json.get("events").get(0).get("content").get("posterPath").isNull()).isTrue();
    }

    @Test
    @DisplayName("Should Expose The Calendar Service Month Contract")
    void shouldExposeTheCalendarServiceMonthContract() throws Exception {
        Method method = CalendarService.class.getMethod("getMonth", UUID.class, YearMonth.class);

        assertThat(method.getReturnType()).isEqualTo(CalendarResponseDTO.class);
        assertThat(method.getParameterTypes()).containsExactly(UUID.class, YearMonth.class);
    }
}
