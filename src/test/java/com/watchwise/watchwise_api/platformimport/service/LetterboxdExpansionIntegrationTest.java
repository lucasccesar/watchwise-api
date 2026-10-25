package com.watchwise.watchwise_api.platformimport.service;

import com.watchwise.watchwise_api.calendar.service.CalendarEpisodeSchedule;
import com.watchwise.watchwise_api.calendar.service.CalendarScheduleKey;
import com.watchwise.watchwise_api.calendar.service.CalendarScheduleLookup;
import com.watchwise.watchwise_api.calendar.service.CalendarScheduleProvider;
import com.watchwise.watchwise_api.calendar.service.CalendarSeasonSchedule;
import com.watchwise.watchwise_api.calendar.service.CalendarSeriesSchedule;
import com.watchwise.watchwise_api.diarycompletion.service.impl.CalendarCompletionScopeLoader;
import com.watchwise.watchwise_api.common.tmdb.TmdbLookupOrigin;
import com.watchwise.watchwise_api.content.dto.ContentRefDTO;
import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.content.service.ContentService;
import com.watchwise.watchwise_api.diaryentry.dto.WatchEventCommand;
import com.watchwise.watchwise_api.platformimport.entity.ImportItem;
import com.watchwise.watchwise_api.platformimport.model.ImportRecordKind;
import com.watchwise.watchwise_api.platformimport.model.ImportTarget;
import com.watchwise.watchwise_api.platformimport.service.ImportLimits;
import com.watchwise.watchwise_api.platformimport.service.impl.ImportPassPlanner;
import com.watchwise.watchwise_api.platformimport.service.impl.LetterboxdSeriesExpansionPlanner;
import com.watchwise.watchwise_api.user.entity.User;
import com.watchwise.watchwise_api.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.mock;

@ExtendWith(MockitoExtension.class)
class LetterboxdExpansionIntegrationTest {

    @Mock private CalendarScheduleProvider calendar;
    @Mock private UserRepository users;
    @Mock private ContentService contents;
    @Mock private ImportPassPlanner passes;
    @Mock private com.watchwise.watchwise_api.platformimport.repository.ImportItemRepository stagedItems;

    private final ObjectMapper mapper = new ObjectMapper();
    private final ImportLimits limits = new ImportLimits();
    private final UUID ownerId = UUID.fromString("00000000-0000-0000-0000-000000000101");
    private final UUID seriesContentId = UUID.fromString("00000000-0000-0000-0000-000000000102");

    @Test
    void includesEpisodesReleasedByImportTimeAndDropsIncompatibleHistoricalDates() throws Exception {
        Instant now = Instant.parse("2026-10-24T18:00:00Z");
        ImportItem source = seriesSource("2020-01-01", "letterboxd://series/42");
        schedule(List.of(
                episode(1, LocalDate.of(2020, 1, 1)),
                episode(2, LocalDate.of(2026, 10, 24)),
                episode(3, LocalDate.of(2026, 10, 25))));
        preparePasses(2);

        var result = planner().plan(ownerId, source, now);

        assertThat(result.children()).extracting(command -> command.content().episodeNumber())
                .containsExactly(1, 2);
        assertThat(result.children()).extracting(WatchEventCommand::watchedDate)
                .containsExactly(LocalDate.of(2020, 1, 1), null);
    }

    @Test
    void doesNotTreatTheLetterboxdRecordDateAsTheDateTheSeriesWasWatched() throws Exception {
        ImportItem source = seriesSource("2020-01-01", "letterboxd://series/record-date-only");
        var payload = (tools.jackson.databind.node.ObjectNode) mapper.readTree(source.getPayloadJson());
        payload.remove("watchedDate");
        payload.put("date", "2020-01-01");
        payload.put("sourceRecordedOn", "2020-01-01");
        source.setPayloadJson(mapper.writeValueAsString(payload));
        schedule(List.of(episode(1, LocalDate.of(2019, 1, 1))));
        preparePasses(1);

        var result = planner().plan(ownerId, source, Instant.parse("2026-10-24T18:00:00Z"));

        assertThat(result.children().getFirst().watchedDate()).isNull();
    }

    @Test
    void leafEpisodeIsKeptAsOneEventAndDoesNotExpandItsSeries() throws Exception {
        ImportItem source = episodeSource(3, 2, "2020-01-01");
        when(contents.getOrCreateReference(any())).thenReturn(contentRef(UUID.randomUUID(), ContentType.EPISODE,
                "42", 3, 2));
        when(passes.plan(eq(ownerId), any())).thenAnswer(invocation -> {
            List<ImportPassPlanner.WatchEvent> events = invocation.getArgument(1);
            return new ImportPassPlanner.Plan(events.stream().collect(java.util.stream.Collectors.toMap(
                    ImportPassPlanner.WatchEvent::itemId, ignored -> 1)), Map.of(), Set.of());
        });

        var result = planner().plan(ownerId, source, Instant.parse("2026-10-24T18:00:00Z"));

        assertThat(result.children()).hasSize(1);
        assertThat(result.children().getFirst().content().type()).isEqualTo(ContentType.EPISODE);
    }

    @Test
    void reportsPredictedTotalWhenExpansionWouldExceedTheJobRecordQuota() throws Exception {
        ImportItem source = seriesSource("2020-01-01", "letterboxd://series/42");
        schedule(List.of(episode(1, LocalDate.of(2020, 1, 1)), episode(2, LocalDate.of(2020, 1, 1))));
        when(stagedItems.countByJobIdAndPlanGeneration(source.getJobId(), source.getPlanGeneration())).thenReturn(499_999L);
        limits.setMaxRecords(500_000);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> planner().plan(ownerId, source,
                        Instant.parse("2026-10-24T18:00:00Z")))
                .isInstanceOf(LetterboxdSeriesExpansionPlanner.SeriesExpansionIssueException.class)
                .hasMessageContaining("500001")
                .hasMessageContaining("IMPORT_VOLUME_LIMIT");
    }

    @Test
    void preservesIndependentlyPlannedEpisodePasses() throws Exception {
        ImportItem source = seriesSource("2020-01-01", "letterboxd://series/independent-passes");
        schedule(List.of(episode(1, LocalDate.of(2019, 1, 1)), episode(2, LocalDate.of(2020, 1, 1))));
        UUID firstEpisodeId = UUID.fromString("00000000-0000-0000-0000-000000000201");
        UUID secondEpisodeId = UUID.fromString("00000000-0000-0000-0000-000000000202");
        when(contents.getOrCreateReference(any())).thenAnswer(invocation -> {
            var ref = (com.watchwise.watchwise_api.content.dto.ContentRefCreationDTO) invocation.getArgument(0);
            UUID id = ref.episodeNumber() == 1 ? firstEpisodeId : secondEpisodeId;
            return contentRef(id, ContentType.EPISODE, "42", ref.seasonNumber(), ref.episodeNumber());
        });
        when(passes.plan(eq(ownerId), any())).thenAnswer(invocation -> {
            List<ImportPassPlanner.WatchEvent> events = invocation.getArgument(1);
            return new ImportPassPlanner.Plan(events.stream().collect(java.util.stream.Collectors.toMap(
                    ImportPassPlanner.WatchEvent::itemId,
                    event -> event.contentId().equals(firstEpisodeId) ? 3 : 2)), Map.of(), Set.of());
        });

        var plan = planner().plan(ownerId, source, Instant.parse("2026-10-24T18:00:00Z"));

        assertThat(plan.children()).extracting(WatchEventCommand::requestedWatchNumber).containsExactly(3, 2);
    }

    @Test
    void reportsUnavailableCalendarAsAnActionableIssue() throws Exception {
        ImportItem source = seriesSource("2020-01-01", "letterboxd://series/unavailable");
        stubOwner();
        when(calendar.loadSeries("42", "US", "en-US")).thenReturn(new CalendarScheduleLookup.Unavailable());

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> planner().plan(ownerId, source,
                        Instant.parse("2026-10-24T18:00:00Z")))
                .hasMessageContaining("SERIES_CALENDAR_UNAVAILABLE")
                .hasMessageContaining("refresh calendar data");
    }

    @Test
    void reportsIncompleteCalendarAsAnActionableIssueDistinctFromUnavailable() throws Exception {
        ImportItem source = seriesSource("2020-01-01", "letterboxd://series/incomplete");
        stubOwner();
        var incomplete = seriesSchedule(List.of(season(1, List.of(episode(1, LocalDate.of(2020, 1, 1))))), false);
        when(calendar.loadSeries("42", "US", "en-US"))
                .thenReturn(new CalendarScheduleLookup.FoundSeries(incomplete));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> planner().plan(ownerId, source,
                        Instant.parse("2026-10-24T18:00:00Z")))
                .hasMessageContaining("SERIES_CALENDAR_INCOMPLETE")
                .hasMessageContaining("complete schedule");
    }

    @Test
    void reportsWhenCompleteCalendarHasNoReleasedRegularEpisodes() throws Exception {
        ImportItem source = seriesSource("2020-01-01", "letterboxd://series/not-yet-released");
        schedule(List.of(episode(1, LocalDate.of(2027, 1, 1))));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> planner().plan(ownerId, source,
                        Instant.parse("2026-10-24T18:00:00Z")))
                .hasMessageContaining("SERIES_EXPANSION_EMPTY")
                .hasMessageContaining("no released regular episodes");
    }

    @Test
    void excludesReleasedSeasonZeroSpecialsFromRegularEpisodeExpansion() throws Exception {
        ImportItem source = seriesSource("2020-01-01", "letterboxd://series/with-special");
        stubOwner();
        CalendarSeasonSchedule specialSchedule = mock(CalendarSeasonSchedule.class);
        when(specialSchedule.seasonNumber()).thenReturn(0);
        when(specialSchedule.episodes()).thenReturn(List.of(episode(1, LocalDate.of(2019, 12, 1))));
        CalendarSeriesSchedule.Season special = mock(CalendarSeriesSchedule.Season.class);
        when(special.schedule()).thenReturn(specialSchedule);
        when(special.expectedEpisodeCount()).thenReturn(1);
        when(special.isFullyRepresented()).thenReturn(true);
        CalendarSeriesSchedule sourceSchedule = mock(CalendarSeriesSchedule.class);
        when(sourceSchedule.key()).thenReturn(new CalendarScheduleKey(ContentType.SERIES, "42", "en-US", "US"));
        when(sourceSchedule.completeSchedule()).thenReturn(true);
        when(sourceSchedule.expectedEpisodeCountsBySeason()).thenReturn(Map.of(1, 1));
        when(sourceSchedule.seasons()).thenReturn(List.of(special,
                season(1, List.of(episode(1, LocalDate.of(2020, 1, 1))))));
        when(calendar.loadSeries("42", "US", "en-US"))
                .thenReturn(new CalendarScheduleLookup.FoundSeries(sourceSchedule));
        preparePasses(1);

        var result = planner().plan(ownerId, source, Instant.parse("2026-10-24T18:00:00Z"));

        assertThat(result.children()).extracting(command -> command.content().seasonNumber()).containsExactly(1);
        assertThat(result.children()).extracting(command -> command.content().episodeNumber()).containsExactly(1);
    }

    @Test
    void usesTheSameScheduleVersionAsTheHistoricalCompletionVerifier() throws Exception {
        Instant now = Instant.parse("2026-10-24T18:00:00Z");
        ImportItem source = seriesSource("2020-01-01", "letterboxd://series/version-contract");
        schedule(List.of(episode(1, LocalDate.of(2020, 1, 1)), episode(2, LocalDate.of(2020, 2, 1))));
        preparePasses(2);

        var plan = planner().plan(ownerId, source, now);
        var completionScope = new CalendarCompletionScopeLoader(calendar)
                .load(com.watchwise.watchwise_api.content.dto.MediaReference.series("42"), "US", "en-US", now);

        assertThat(plan.scopeHash()).isEqualTo(completionScope.scheduleVersion());
        assertThat(plan.scopeHash()).startsWith("sha256:");
    }

    private LetterboxdSeriesExpansionPlanner planner() {
        return new LetterboxdSeriesExpansionPlanner(calendar, users, contents, passes, mapper,
                new com.watchwise.watchwise_api.platformimport.service.impl.TheaterTagMatcher(), stagedItems, limits);
    }

    private ImportItem seriesSource(String watchedDate, String sourceId) throws Exception {
        return seriesSource(watchedDate, sourceId, UUID.randomUUID());
    }

    private ImportItem seriesSource(String watchedDate, String sourceId, UUID jobId) throws Exception {
        ImportItem item = ImportItem.builder().id(UUID.randomUUID()).jobId(jobId).planGeneration(0)
                .normalizedKind(com.watchwise.watchwise_api.platformimport.model.ImportRecordKind.SERIES_EXPANSION)
                .sourceNamespace("letterboxd:account").objectKind("diary_event").sourceId(sourceId)
                .sourceEventKey(sourceId).payloadJson("{\"watchedDate\":\"" + watchedDate + "\"}")
                .resolvedTargetJson(mapper.writeValueAsString(new ImportTarget.ContentTarget(
                        com.watchwise.watchwise_api.content.dto.MediaReference.series("42"))))
                .ordinal(1).build();
        return item;
    }

    private ImportItem episodeSource(int season, int episode, String watchedDate) throws Exception {
        ImportItem item = seriesSource(watchedDate, "letterboxd://diary/leaf");
        item.setNormalizedKind(com.watchwise.watchwise_api.platformimport.model.ImportRecordKind.DIARY_EVENT);
        item.setResolvedTargetJson(mapper.writeValueAsString(new ImportTarget.ContentTarget(
                com.watchwise.watchwise_api.content.dto.MediaReference.episode("42", season, episode))));
        return item;
    }

    private void schedule(List<CalendarEpisodeSchedule> episodes) {
        scheduleSeasons(List.of(season(1, episodes)));
    }

    private CalendarSeriesSchedule.Season season(int seasonNumber, List<CalendarEpisodeSchedule> episodes) {
        var season = new CalendarSeasonSchedule("42", seasonNumber, "US", "en-US", "Series", null, episodes);
        return new CalendarSeriesSchedule.Season(season, episodes.size(), TmdbLookupOrigin.REMOTE);
    }

    private void scheduleSeasons(List<CalendarSeriesSchedule.Season> seasons) {
        scheduleSeasons(seasons, true);
    }

    private CalendarSeriesSchedule seriesSchedule(List<CalendarSeriesSchedule.Season> seasons, boolean complete) {
        Map<Integer, Integer> counts = seasons.stream().collect(java.util.stream.Collectors.toMap(
                item -> item.schedule().seasonNumber(), CalendarSeriesSchedule.Season::expectedEpisodeCount));
        return new CalendarSeriesSchedule(
                new CalendarScheduleKey(ContentType.SERIES, "42", "en-US", "US"),
                seasons, counts, counts.values().stream().mapToInt(Integer::intValue).sum(), complete);
    }

    private void scheduleSeasons(List<CalendarSeriesSchedule.Season> seasons, boolean complete) {
        User owner = User.builder().id(ownerId).username("test-user").name("Test User")
                .email("test@example.test").password("encoded").preferredLanguage("en-US")
                .preferredRegion("US").build();
        when(users.findById(ownerId)).thenReturn(java.util.Optional.of(owner));
        var series = seriesSchedule(seasons, complete);
        when(calendar.loadSeries("42", "US", "en-US")).thenReturn(new CalendarScheduleLookup.FoundSeries(series));
    }

    private void stubOwner() {
        User owner = User.builder().id(ownerId).username("test-user").name("Test User")
                .email("test@example.test").password("encoded").preferredLanguage("en-US")
                .preferredRegion("US").build();
        when(users.findById(ownerId)).thenReturn(java.util.Optional.of(owner));
    }

    private void preparePasses(int count) {
        when(contents.getOrCreateReference(any())).thenAnswer(invocation -> {
            var dto = (com.watchwise.watchwise_api.content.dto.ContentRefCreationDTO) invocation.getArgument(0);
            int episode = dto.episodeNumber();
            return contentRef(UUID.nameUUIDFromBytes(("episode-" + episode).getBytes()), ContentType.EPISODE,
                    "42", dto.seasonNumber(), episode);
        });
        when(passes.plan(eq(ownerId), any())).thenAnswer(invocation -> {
            List<ImportPassPlanner.WatchEvent> events = invocation.getArgument(1);
            return new ImportPassPlanner.Plan(events.stream().collect(java.util.stream.Collectors.toMap(
                    ImportPassPlanner.WatchEvent::itemId, ignored -> 1)), Map.of(), Set.of());
        });
    }

    private ContentRefDTO contentRef(UUID id, ContentType type, String seriesId, int season, int episode) {
        return new ContentRefDTO(id, null, type, seriesId, season, episode, false, false, null, null);
    }

    private CalendarEpisodeSchedule episode(int number, LocalDate releaseDate) {
        return new CalendarEpisodeSchedule(number, "Episode " + number, releaseDate, (LocalTime) null, null, null, null);
    }
}
