package com.watchwise.watchwise_api.contentreview.service;

import com.watchwise.watchwise_api.content.service.impl.ContentPageStatsServiceImpl;
import com.watchwise.watchwise_api.content.service.impl.ContentStatsServiceImpl;
import com.watchwise.watchwise_api.summary.repository.ProfileSummaryQueryRepository;
import com.watchwise.watchwise_api.summary.repository.AllTimeStatsQueryRepository;
import com.watchwise.watchwise_api.diaryentry.repository.DiaryEntryRepository;
import com.watchwise.watchwise_api.contentreview.repository.ContentReviewRepository;
import java.time.LocalDateTime;
import java.time.LocalDate;
import org.springframework.data.domain.PageRequest;
import com.watchwise.watchwise_api.content.entity.ContentType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({ContentStatsServiceImpl.class, ContentPageStatsServiceImpl.class})
@Testcontainers
class OpinionReadConsumersIntegrationTest {
    @Container static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired ContentStatsServiceImpl stats;
    @Autowired ContentPageStatsServiceImpl pageStats;
    @Autowired ProfileSummaryQueryRepository profileQueries;
    @Autowired AllTimeStatsQueryRepository allTimeQueries;
    @Autowired DiaryEntryRepository diaryEntries;
    @Autowired ContentReviewRepository reviews;

    @Test
    void twoWrittenReviewsFromOneOwnerCountAsOneRatingVote() {
        UUID owner = user(true);
        UUID other = user(true);
        UUID privateOwner = user(false);
        UUID series = content("SERIES", "33001", null, null, 300);
        rating(owner, series, 8);
        rating(other, series, 6);
        rating(privateOwner, series, 10);
        review(owner, series, 8);
        review(owner, series, 9);
        review(other, series, 6);

        var basic = stats.getStats(series);
        var page = pageStats.getStats(series);

        assertThat(basic.averageScore()).isEqualTo(7.0);
        assertThat(basic.reviewsCount()).isEqualTo(3);
        assertThat(page.ratingsCount()).isEqualTo(2);
        assertThat(page.ratingsDistribution()).filteredOn(bucket -> bucket.score() == 8)
                .singleElement().extracting(bucket -> bucket.count()).isEqualTo(1L);
    }

    @Test
    void runtimeCountsMovieAndEpisodeWatchesWithoutSeriesMarker() {
        UUID owner = user(true);
        UUID movie = content("MOVIE", "33002", null, null, 120);
        UUID series = content("SERIES", "33003", null, null, 300);
        UUID episode = content("EPISODE", null, "33003", 1, 40);
        watched(owner, movie);
        watched(owner, series);
        watched(owner, episode);

        assertThat(profileQueries.sumRuntimeMinutesByUserIdAndContentType(owner, ContentType.MOVIE)).isEqualTo(120);
        assertThat(profileQueries.sumRuntimeMinutesByUserIdAndContentType(owner, ContentType.EPISODE)).isEqualTo(40);
    }

    @Test
    void reviewPageFiltersPrivateAuthorsBeforePaginationAndOmitsMigratedLegacyRow() {
        UUID viewer = user(true);
        UUID publicOwner = user(true);
        UUID privateOwner = user(false);
        UUID series = content("SERIES", "33004", null, null, 300);
        UUID publicReview = review(publicOwner, series, 8);
        review(privateOwner, series, 9);
        UUID oldDiaryId = watched(publicOwner, series);
        jdbc.update("update diary_entries set comment = 'legacy' where id = ?", oldDiaryId);
        jdbc.update("insert into legacy_diary_migration_maps(id,old_diary_entry_id,user_id,destination_kind,destination_id) values (?,?,?,'CONTENT_REVIEW',?)",
                UUID.randomUUID(), oldDiaryId, publicOwner, publicReview);

        var page = diaryEntries.findContentReviewKeys(series, viewer, PageRequest.of(0, 1));

        assertThat(page.getTotalElements()).isEqualTo(1);
        assertThat(page.getContent()).extracting(DiaryEntryRepository.ContentReviewKey::getReviewId)
                .containsExactly(publicReview);
    }

    @Test
    void feedOrdersImportedReviewsBySourcePublicationAndFiltersBlockedAuthorsBeforeLimit() {
        UUID viewer = user(true);
        UUID owner = user(false);
        UUID blocked = user(true);
        UUID series = content("SERIES", "33005", null, null, 300);
        jdbc.update("insert into followers(id,follower_id,followed_id,status) values (?,?,?,'ACCEPTED')",
                UUID.randomUUID(), viewer, owner);
        jdbc.update("insert into followers(id,follower_id,followed_id,status) values (?,?,?,'ACCEPTED')",
                UUID.randomUUID(), viewer, blocked);
        UUID oldReview = review(owner, series, 6);
        UUID recentReview = review(owner, series, 8);
        UUID blockedReview = review(blocked, series, 10);
        jdbc.update("update content_reviews set source_published_at = '2020-01-01T12:00:00Z' where id = ?", oldReview);
        jdbc.update("update content_reviews set source_published_at = '2025-01-01T12:00:00Z' where id = ?", recentReview);
        jdbc.update("update content_reviews set source_published_at = '2026-01-01T12:00:00Z' where id = ?", blockedReview);
        jdbc.update("insert into user_blocks(id,blocker_id,blocked_id,created_at) values (?,?,?,CURRENT_TIMESTAMP)",
                UUID.randomUUID(), blocked, viewer);

        var first = reviews.findFeedCandidates(java.util.List.of(owner, blocked), viewer,
                null, null, PageRequest.of(0, 1));
        var second = reviews.findFeedCandidates(java.util.List.of(owner, blocked), viewer,
                LocalDateTime.of(2025, 1, 1, 12, 0), recentReview, PageRequest.of(0, 1));

        assertThat(first).extracting(review -> review.getId()).containsExactly(recentReview);
        assertThat(second).extracting(review -> review.getId()).containsExactly(oldReview);
    }

    @Test
    void profileCountsCurrentSeriesRatingAndCompletionPassesInsteadOfLegacyReviewRows() {
        UUID owner = user(true);
        UUID series = content("SERIES", "33006", null, null, 300);
        UUID season = content("SEASON", null, "33006", 1, 300);
        rating(owner, series, 8);
        jdbc.update("insert into content_ratings(id,user_id,content_id,content_type,score) values (?,?,?,'SEASON',?)",
                UUID.randomUUID(), owner, season, 7);
        review(owner, series, 6);
        review(owner, series, 8);
        jdbc.update("insert into user_series_history_states(user_id,series_tmdb_id,history_revision,computed_revision,schedule_version) values (?, '33006', 1, 1, 1)", owner);
        jdbc.update("insert into diary_completion_projections(id,user_id,content_id,content_type,watch_number,ordered_at,history_revision,schedule_version) values (?,?,?,'SERIES',1,CURRENT_TIMESTAMP,1,1)",
                UUID.randomUUID(), owner, series);
        jdbc.update("insert into diary_completion_projections(id,user_id,content_id,content_type,watch_number,ordered_at,history_revision,schedule_version) values (?,?,?,'SEASON',1,CURRENT_TIMESTAMP,1,999)",
                UUID.randomUUID(), owner, season);

        assertThat(profileQueries.countLatestScoresByUserIdAndContentType(owner, "SERIES"))
                .singleElement().satisfies(row -> {
                    assertThat(row.getScore()).isEqualTo(8);
                    assertThat(row.getCount()).isEqualTo(1L);
                });
        assertThat(profileQueries.countDiaryEntriesGroupByContentType(owner, "SERIES", PageRequest.of(0, 10)))
                .singleElement().satisfies(row -> {
                    assertThat(row.getContentId()).isEqualTo(series);
                    assertThat(row.getCount()).isEqualTo(1L);
                });
        assertThat(profileQueries.countLatestScoresByUserIdAndContentType(owner, "SEASON"))
                .singleElement().satisfies(row -> {
                    assertThat(row.getScore()).isEqualTo(7);
                    assertThat(row.getCount()).isEqualTo(1L);
                });
        assertThat(profileQueries.countDiaryEntriesGroupByContentType(owner, "SEASON", PageRequest.of(0, 10)))
                .singleElement().satisfies(row -> {
                    assertThat(row.getContentId()).isEqualTo(season);
                    assertThat(row.getCount()).isEqualTo(1L);
                });
        assertThat(profileQueries.countDiaryEntriesByUserIdAndContentIdsAndContentType(
                owner, java.util.List.of(season), "SEASON"))
                .singleElement().satisfies(row -> {
                    assertThat(row.getContentId()).isEqualTo(season);
                    assertThat(row.getCount()).isEqualTo(1L);
                });
    }

    @Test
    void historicalSeriesCompletionProjectionKeepsSeriesSummariesWithoutInflatingEpisodeRuntime() {
        UUID owner = user(true);
        UUID series = content("SERIES", "33007", null, null, 300);
        jdbc.update("update contents set genres = ARRAY['Drama'], release_year = 2012, countries = ARRAY['US'] where id = ?",
                series);
        LocalDate completedOn = java.time.LocalDate.of(2024, 2, 3);
        jdbc.update("insert into diary_completion_projections(id,user_id,content_id,content_type,watch_number,ordered_at,completed_on,history_revision,schedule_version) values (?,?,?,'SERIES',1,CURRENT_TIMESTAMP,?,1,1)",
                UUID.randomUUID(), owner, series, completedOn);

        assertThat(diaryEntries.countDistinctTitlesByGenreAndUserIdForSeries(owner))
                .singleElement().satisfies(row -> {
                    assertThat(row.getGenre()).isEqualTo("Drama");
                    assertThat(row.getCount()).isEqualTo(1L);
                });
        assertThat(diaryEntries.countDistinctTitlesByGenreAndUserIdForSeriesAndWatchedDateBetween(
                owner, completedOn.minusDays(1), completedOn.plusDays(1)))
                .singleElement().satisfies(row -> {
                    assertThat(row.getGenre()).isEqualTo("Drama");
                    assertThat(row.getCount()).isEqualTo(1L);
                });
        assertThat(allTimeQueries.countDistinctSeriesByDecade(owner))
                .singleElement().satisfies(row -> {
                    assertThat(row.getDecade()).isEqualTo(2010);
                    assertThat(row.getCount()).isEqualTo(1L);
                });
        assertThat(allTimeQueries.countDistinctSeriesByCountry(owner))
                .singleElement().satisfies(row -> {
                    assertThat(row.getCountry()).isEqualTo("US");
                    assertThat(row.getCount()).isEqualTo(1L);
                });
        assertThat(diaryEntries.findHomeWatchAggregate(owner).getDistinctSeriesWatched()).isEqualTo(1L);
        assertThat(profileQueries.sumRuntimeMinutesByUserIdAndContentType(owner, ContentType.EPISODE)).isZero();
    }

    private UUID user(boolean isPublic) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into users(id, username, email, password, profile_picture, name, is_profile_public) values (?, ?, ?, 'hash', 'picture', 'Test', ?)",
                id, id.toString(), id + "@example.com", isPublic);
        return id;
    }

    private UUID content(String type, String tmdbId, String seriesTmdbId, Integer season, Integer runtime) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into contents(id,type,tmdb_id,series_tmdb_id,season_number,episode_number,runtime_minutes) values (?,?,?,?,?,?,?)",
                id, type, tmdbId, seriesTmdbId, season, type.equals("EPISODE") ? 1 : null, runtime);
        return id;
    }

    private void rating(UUID owner, UUID content, int score) {
        jdbc.update("insert into content_ratings(id,user_id,content_id,content_type,score) values (?,?,?,'SERIES',?)",
                UUID.randomUUID(), owner, content, score);
    }

    private UUID review(UUID owner, UUID content, int score) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into content_reviews(id,user_id,content_id,content_type,publication_kind,context_kind,comment,score_at_publication,watch_number) values (?,?,?,'SERIES','WRITTEN_REVIEW','COMPLETION','review',?,1)",
                id, owner, content, score);
        return id;
    }

    private UUID watched(UUID owner, UUID content) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into diary_entries(id,user_id,content_id,watch_number,created_at,updated_at) values (?,?,?,1,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                id, owner, content);
        return id;
    }
}
