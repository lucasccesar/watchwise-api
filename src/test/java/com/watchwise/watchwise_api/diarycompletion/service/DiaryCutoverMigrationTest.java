package com.watchwise.watchwise_api.diarycompletion.service;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
class DiaryCutoverMigrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Test
    void removesLegacyStateAndPreservesEpisodeHistoryAndIndependentOpinions() {
        migrateThrough("81");
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));

        UUID ownerId = insertUser(jdbc, "owner");
        UUID socialUserId = insertUser(jdbc, "social");
        UUID seriesId = insertContent(jdbc, "SERIES", "1396", null, null, null);
        UUID seasonId = insertContent(jdbc, "SEASON", null, "1396", 1, null);
        UUID episodeId = insertContent(jdbc, "EPISODE", null, "1396", 1, 1);
        UUID movieId = insertContent(jdbc, "MOVIE", "550", null, null, null);

        UUID seriesEntryId = insertLegacyDiaryEntry(jdbc, ownerId, seriesId, null, null, true);
        UUID seasonEntryId = insertLegacyDiaryEntry(jdbc, ownerId, seasonId, null, null, false);
        UUID episodeEntryId = insertDiaryEntry(jdbc, ownerId, episodeId, 8, "episode opinion");
        UUID movieEntryId = insertDiaryEntry(jdbc, ownerId, movieId, 9, "movie opinion");

        UUID seriesRatingId = UUID.randomUUID();
        UUID seasonRatingId = UUID.randomUUID();
        insertRating(jdbc, seriesRatingId, ownerId, seriesId, "SERIES", 7);
        insertRating(jdbc, seasonRatingId, ownerId, seasonId, "SEASON", 6);

        UUID writtenReviewId = insertReview(jdbc, ownerId, seriesId, "SERIES", "WRITTEN_REVIEW", "UNKNOWN", 1, "written review");
        UUID legacyActivityReviewId = insertReview(jdbc, ownerId, seriesId, "SERIES", "LEGACY_ACTIVITY", "COMPLETION", null, null);
        UUID legacyContextReviewId = insertReview(jdbc, ownerId, seasonId, "SEASON", "WRITTEN_REVIEW", "LEGACY", 1, "legacy context");

        UUID diaryCommentId = insertComment(jdbc, socialUserId, "diary_entry_id", seriesEntryId, null);
        UUID diaryReplyId = insertComment(jdbc, socialUserId, "content_id", seriesId, diaryCommentId);
        UUID diaryGrandchildId = insertComment(jdbc, socialUserId, "content_id", seriesId, diaryReplyId);
        UUID legacyReviewCommentId = insertComment(jdbc, socialUserId, "content_review_id", legacyActivityReviewId, null);
        UUID legacyReviewReplyId = insertComment(jdbc, socialUserId, "content_id", seriesId, legacyReviewCommentId);
        UUID legacyContextReviewCommentId = insertComment(jdbc, socialUserId, "content_review_id", legacyContextReviewId, null);
        UUID legacyContextReviewReplyId = insertComment(jdbc, socialUserId, "content_id", seasonId, legacyContextReviewCommentId);
        UUID preservedReviewCommentId = insertComment(jdbc, socialUserId, "content_review_id", writtenReviewId, null);
        UUID preservedContentCommentId = insertComment(jdbc, socialUserId, "content_id", seriesId, null);

        UUID diaryLikeId = insertLike(jdbc, socialUserId, "diary_entry_id", seriesEntryId);
        UUID diaryReplyLikeId = insertLike(jdbc, socialUserId, "comment_id", diaryGrandchildId);
        UUID legacyReviewLikeId = insertLike(jdbc, socialUserId, "content_review_id", legacyActivityReviewId);
        UUID legacyContextReviewLikeId = insertLike(jdbc, socialUserId, "content_review_id", legacyContextReviewId);
        UUID writtenReviewLikeId = insertLike(jdbc, socialUserId, "content_review_id", writtenReviewId);
        UUID companionId = UUID.randomUUID();
        jdbc.update("INSERT INTO watch_companions (id, diary_entry_id, user_id) VALUES (?, ?, ?)",
                companionId, seasonEntryId, socialUserId);

        UUID diaryReportId = insertReport(jdbc, "REVIEW", seriesEntryId, socialUserId);
        UUID seasonReportId = insertReport(jdbc, "REVIEW", seasonEntryId, socialUserId);
        UUID diaryReplyReportId = insertReport(jdbc, "COMMENT", diaryGrandchildId, socialUserId);
        UUID legacyReviewReportId = insertReport(jdbc, "CONTENT_REVIEW", legacyActivityReviewId, socialUserId);
        UUID legacyReviewReplyReportId = insertReport(jdbc, "COMMENT", legacyReviewReplyId, socialUserId);
        UUID legacyContextReviewReportId = insertReport(jdbc, "CONTENT_REVIEW", legacyContextReviewId, socialUserId);
        UUID writtenReviewReportId = insertReport(jdbc, "CONTENT_REVIEW", writtenReviewId, socialUserId);
        UUID contentCommentReportId = insertReport(jdbc, "COMMENT", preservedContentCommentId, socialUserId);

        UUID diaryNotificationId = insertSocialNotification(jdbc, ownerId, "DIARY_ENTRY", seriesEntryId);
        UUID seasonNotificationId = insertSocialNotification(jdbc, ownerId, "DIARY_ENTRY", seasonEntryId);
        UUID diaryReplyNotificationId = insertSocialNotification(jdbc, ownerId, "COMMENT", diaryGrandchildId);
        UUID legacyReviewNotificationId = insertSocialNotification(jdbc, ownerId, "CONTENT_REVIEW", legacyActivityReviewId);
        UUID legacyReviewReplyNotificationId = insertSocialNotification(jdbc, ownerId, "COMMENT", legacyReviewReplyId);
        UUID legacyContextReviewNotificationId = insertSocialNotification(jdbc, ownerId, "CONTENT_REVIEW", legacyContextReviewId);
        UUID diaryReportNotificationId = insertReportNotification(jdbc, ownerId, diaryReportId);
        UUID reviewReportNotificationId = insertReportNotification(jdbc, ownerId, legacyReviewReportId);
        UUID writtenReviewNotificationId = insertSocialNotification(jdbc, ownerId, "CONTENT_REVIEW", writtenReviewId);
        UUID writtenReportNotificationId = insertReportNotification(jdbc, ownerId, writtenReviewReportId);

        UUID seriesAssertionId = insertAssertion(jdbc, ownerId, seriesId, "series");
        UUID seasonAssertionId = insertAssertion(jdbc, ownerId, seasonId, "season");
        UUID episodeAssertionId = insertAssertion(jdbc, ownerId, episodeId, "episode");
        UUID movieAssertionId = insertAssertion(jdbc, ownerId, movieId, "movie");
        UUID seriesEntryLinkId = insertImportLink(jdbc, ownerId, "DIARY_ENTRY", seriesEntryId, "series-entry");
        UUID seasonEntryLinkId = insertImportLink(jdbc, ownerId, "DIARY_ENTRY", seasonEntryId, "season-entry");
        UUID activityReviewLinkId = insertImportLink(jdbc, ownerId, "CONTENT_REVIEW", legacyActivityReviewId, "activity-review");
        UUID seasonReviewLinkId = insertImportLink(jdbc, ownerId, "CONTENT_REVIEW", legacyContextReviewId, "season-review");
        UUID seriesAssertionLinkId = insertImportLink(jdbc, ownerId, "IMPORTED_WATCH_ASSERTION", seriesAssertionId, "series-assertion");
        UUID seasonAssertionLinkId = insertImportLink(jdbc, ownerId, "IMPORTED_WATCH_ASSERTION", seasonAssertionId, "season-assertion");
        UUID episodeEntryLinkId = insertImportLink(jdbc, ownerId, "DIARY_ENTRY", episodeEntryId, "episode-entry");
        UUID episodeAssertionLinkId = insertImportLink(jdbc, ownerId, "IMPORTED_WATCH_ASSERTION", episodeAssertionId, "episode-assertion");

        UUID projectionId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO diary_completion_projections
                    (id, user_id, content_id, content_type, watch_number, anchor_diary_entry_id,
                     completed_on, ordered_at, history_revision, schedule_version)
                VALUES (?, ?, ?, 'SEASON', 1, ?, DATE '2026-01-01', TIMESTAMPTZ '2026-01-01 00:00:00Z', 1, 1)
                """, projectionId, ownerId, seasonId, seasonEntryId);
        jdbc.update("""
                INSERT INTO user_series_history_states
                    (user_id, series_tmdb_id, history_revision, computed_revision, schedule_version)
                VALUES (?, '1396', 17, 17, 3)
                """, ownerId);
        jdbc.update("""
                INSERT INTO user_action_receipts
                    (id, user_id, operation, idempotency_key, request_hash, result_json)
                VALUES (?, ?, 'DIARY_BULK', 'old-key', 'hash', ?)
                """, UUID.randomUUID(), ownerId, "{\"diaryEntryId\":\"" + seriesEntryId + "\"}");
        insertLegacySnapshotAndMap(jdbc, ownerId, seriesId, seriesEntryId);

        migrateThroughLatest();

        assertThat(count(jdbc, "diary_entries", seriesEntryId)).isZero();
        assertThat(count(jdbc, "diary_entries", seasonEntryId)).isZero();
        assertThat(count(jdbc, "diary_entries", episodeEntryId)).isEqualTo(1);
        assertThat(count(jdbc, "diary_entries", movieEntryId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM contents", Integer.class)).isEqualTo(4);
        assertThat(count(jdbc, "content_ratings", seriesRatingId)).isEqualTo(1);
        assertThat(count(jdbc, "content_ratings", seasonRatingId)).isEqualTo(1);
        assertThat(count(jdbc, "content_reviews", writtenReviewId)).isEqualTo(1);
        assertThat(count(jdbc, "content_reviews", legacyActivityReviewId)).isZero();
        assertThat(count(jdbc, "content_reviews", legacyContextReviewId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT context_kind FROM content_reviews WHERE id = ?", String.class,
                legacyContextReviewId)).isEqualTo("UNKNOWN");

        assertThat(count(jdbc, "comments", diaryCommentId)).isZero();
        assertThat(count(jdbc, "comments", diaryReplyId)).isZero();
        assertThat(count(jdbc, "comments", diaryGrandchildId)).isZero();
        assertThat(count(jdbc, "comments", legacyReviewCommentId)).isZero();
        assertThat(count(jdbc, "comments", legacyReviewReplyId)).isZero();
        assertThat(count(jdbc, "comments", legacyContextReviewCommentId)).isEqualTo(1);
        assertThat(count(jdbc, "comments", legacyContextReviewReplyId)).isEqualTo(1);
        assertThat(count(jdbc, "comments", preservedReviewCommentId)).isEqualTo(1);
        assertThat(count(jdbc, "comments", preservedContentCommentId)).isEqualTo(1);
        assertThat(count(jdbc, "likes", diaryLikeId)).isZero();
        assertThat(count(jdbc, "likes", diaryReplyLikeId)).isZero();
        assertThat(count(jdbc, "likes", legacyReviewLikeId)).isZero();
        assertThat(count(jdbc, "likes", legacyContextReviewLikeId)).isEqualTo(1);
        assertThat(count(jdbc, "likes", writtenReviewLikeId)).isEqualTo(1);
        assertThat(count(jdbc, "watch_companions", companionId)).isZero();

        assertThat(count(jdbc, "report_cases", diaryReportId)).isZero();
        assertThat(count(jdbc, "report_cases", seasonReportId)).isZero();
        assertThat(count(jdbc, "report_cases", diaryReplyReportId)).isZero();
        assertThat(count(jdbc, "report_cases", legacyReviewReportId)).isZero();
        assertThat(count(jdbc, "report_cases", legacyReviewReplyReportId)).isZero();
        assertThat(count(jdbc, "report_cases", legacyContextReviewReportId)).isEqualTo(1);
        assertThat(count(jdbc, "report_cases", writtenReviewReportId)).isEqualTo(1);
        assertThat(count(jdbc, "report_cases", contentCommentReportId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM report_submissions", Integer.class)).isEqualTo(3);
        assertThat(count(jdbc, "notifications", diaryNotificationId)).isZero();
        assertThat(count(jdbc, "notifications", seasonNotificationId)).isZero();
        assertThat(count(jdbc, "notifications", diaryReplyNotificationId)).isZero();
        assertThat(count(jdbc, "notifications", legacyReviewNotificationId)).isZero();
        assertThat(count(jdbc, "notifications", legacyReviewReplyNotificationId)).isZero();
        assertThat(count(jdbc, "notifications", legacyContextReviewNotificationId)).isEqualTo(1);
        assertThat(count(jdbc, "notifications", diaryReportNotificationId)).isZero();
        assertThat(count(jdbc, "notifications", reviewReportNotificationId)).isZero();
        assertThat(count(jdbc, "notifications", writtenReviewNotificationId)).isEqualTo(1);
        assertThat(count(jdbc, "notifications", writtenReportNotificationId)).isEqualTo(1);

        assertThat(count(jdbc, "imported_watch_assertions", seriesAssertionId)).isZero();
        assertThat(count(jdbc, "imported_watch_assertions", seasonAssertionId)).isZero();
        assertThat(count(jdbc, "imported_watch_assertions", episodeAssertionId)).isEqualTo(1);
        assertThat(count(jdbc, "imported_watch_assertions", movieAssertionId)).isEqualTo(1);
        assertThat(count(jdbc, "external_import_links", seriesEntryLinkId)).isZero();
        assertThat(count(jdbc, "external_import_links", seasonEntryLinkId)).isZero();
        assertThat(count(jdbc, "external_import_links", activityReviewLinkId)).isZero();
        assertThat(count(jdbc, "external_import_links", seasonReviewLinkId)).isEqualTo(1);
        assertThat(count(jdbc, "external_import_links", seriesAssertionLinkId)).isZero();
        assertThat(count(jdbc, "external_import_links", seasonAssertionLinkId)).isZero();
        assertThat(count(jdbc, "external_import_links", episodeEntryLinkId)).isEqualTo(1);
        assertThat(count(jdbc, "external_import_links", episodeAssertionLinkId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM external_import_links WHERE state = 'TOMBSTONE'", Integer.class)).isZero();

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM diary_completion_projections", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_series_history_states", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_action_receipts", Integer.class)).isZero();
        assertThat(tableExists(jdbc, "legacy_diary_snapshots")).isFalse();
        assertThat(tableExists(jdbc, "legacy_diary_migration_maps")).isFalse();
        assertThat(columnExists(jdbc, "diary_entries", "auto_generated")).isFalse();

        assertDiaryAndReviewGuards(jdbc, ownerId, seriesId, seasonId, episodeEntryId, writtenReviewId);
    }

    private void migrateThrough(String version) {
        Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration")
                .target(version)
                .load()
                .migrate();
    }

    private void migrateThroughLatest() {
        Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();
    }

    private int count(JdbcTemplate jdbc, String table, UUID id) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE id = ?", Integer.class, id);
    }

    private UUID insertUser(JdbcTemplate jdbc, String label) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id, username, email, password, profile_picture, name) "
                        + "VALUES (?, ?, ?, 'hash', '', 'Cutover Test')",
                id, "diary-cutover-" + label + "-" + id, id + "@example.com");
        return id;
    }

    private UUID insertContent(JdbcTemplate jdbc, String type, String tmdbId, String seriesTmdbId,
            Integer seasonNumber, Integer episodeNumber) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO contents (id, tmdb_id, type, series_tmdb_id, season_number, episode_number) "
                        + "VALUES (?, ?, ?, ?, ?, ?)",
                id, tmdbId, type, seriesTmdbId, seasonNumber, episodeNumber);
        return id;
    }

    private UUID insertLegacyDiaryEntry(JdbcTemplate jdbc, UUID userId, UUID contentId, Integer score,
            String comment, boolean autoGenerated) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO diary_entries (id, user_id, content_id, watch_number, score, comment, auto_generated) "
                        + "VALUES (?, ?, ?, 1, ?, ?, ?)",
                id, userId, contentId, score, comment, autoGenerated);
        return id;
    }

    private UUID insertDiaryEntry(JdbcTemplate jdbc, UUID userId, UUID contentId, Integer score, String comment) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO diary_entries (id, user_id, content_id, watch_number, score, comment) "
                        + "VALUES (?, ?, ?, 1, ?, ?)",
                id, userId, contentId, score, comment);
        return id;
    }

    private void insertRating(JdbcTemplate jdbc, UUID id, UUID userId, UUID contentId, String type, int score) {
        jdbc.update("INSERT INTO content_ratings (id, user_id, content_id, content_type, score) VALUES (?, ?, ?, ?, ?)",
                id, userId, contentId, type, score);
    }

    private UUID insertReview(JdbcTemplate jdbc, UUID userId, UUID contentId, String type,
            String publicationKind, String contextKind, Integer watchNumber, String comment) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO content_reviews
                    (id, user_id, content_id, content_type, publication_kind, context_kind, watch_number, comment)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, id, userId, contentId, type, publicationKind, contextKind, watchNumber, comment);
        return id;
    }

    private UUID insertComment(JdbcTemplate jdbc, UUID userId, String targetColumn, UUID targetId, UUID parentId) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO comments (id, user_id, " + targetColumn + ", parent_comment_id, text) "
                        + "VALUES (?, ?, ?, ?, 'cutover fixture')",
                id, userId, targetId, parentId);
        return id;
    }

    private UUID insertLike(JdbcTemplate jdbc, UUID userId, String targetColumn, UUID targetId) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO likes (id, user_id, " + targetColumn + ") VALUES (?, ?, ?)",
                id, userId, targetId);
        return id;
    }

    private UUID insertReport(JdbcTemplate jdbc, String targetType, UUID targetId, UUID reporterId) {
        UUID caseId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO report_cases (id, target_type, target_id, status, created_at, updated_at)
                VALUES (?, ?, ?, 'OPEN', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, caseId, targetType, targetId);
        jdbc.update("""
                INSERT INTO report_submissions (id, case_id, reporter_id, reason, target_snapshot, created_at)
                VALUES (?, ?, ?, 'OTHER', 'cutover fixture', CURRENT_TIMESTAMP)
                """, UUID.randomUUID(), caseId, reporterId);
        return caseId;
    }

    private UUID insertSocialNotification(JdbcTemplate jdbc, UUID recipientId, String targetType, UUID targetId) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO notifications (id, user_id, type, message, target_type, target_id, created_at, updated_at)
                VALUES (?, ?, 'COMMENT_RECEIVED', 'cutover fixture', ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, id, recipientId, targetType, targetId);
        return id;
    }

    private UUID insertReportNotification(JdbcTemplate jdbc, UUID recipientId, UUID reportCaseId) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO notifications (id, user_id, type, message, target_type, target_id, created_at, updated_at)
                VALUES (?, ?, 'REPORT_RESOLVED', 'cutover fixture', 'REPORT_CASE', ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, id, recipientId, reportCaseId);
        return id;
    }

    private UUID insertAssertion(JdbcTemplate jdbc, UUID userId, UUID contentId, String label) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO imported_watch_assertions
                    (id, user_id, content_id, source_namespace, object_kind, source_id)
                VALUES (?, ?, ?, 'LETTERBOXD', 'WATCH', ?)
                """, id, userId, contentId, label);
        return id;
    }

    private UUID insertImportLink(JdbcTemplate jdbc, UUID userId, String targetType, UUID targetId, String label) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO external_import_links
                    (id, user_id, source_namespace, object_kind, source_id, state, target_type, target_id)
                VALUES (?, ?, 'LETTERBOXD', 'WATCH', ?, 'APPLIED', ?, ?)
                """, id, userId, label, targetType, targetId);
        return id;
    }

    private void insertLegacySnapshotAndMap(JdbcTemplate jdbc, UUID userId, UUID contentId, UUID diaryEntryId) {
        UUID snapshotId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO legacy_diary_snapshots
                    (id, old_diary_entry_id, user_id, original_content_id, original_content_type,
                     auto_generated, ignored, created_at, updated_at)
                VALUES (?, ?, ?, ?, 'SERIES', TRUE, FALSE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, snapshotId, diaryEntryId, userId, contentId);
        jdbc.update("""
                INSERT INTO legacy_diary_migration_maps
                    (id, old_diary_entry_id, user_id, destination_kind, destination_id)
                VALUES (?, ?, ?, 'LEGACY_SNAPSHOT', ?)
                """, UUID.randomUUID(), diaryEntryId, userId, snapshotId);
    }

    private boolean tableExists(JdbcTemplate jdbc, String table) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM information_schema.tables
                    WHERE table_schema = 'public' AND table_name = ?)
                """, Boolean.class, table));
    }

    private boolean columnExists(JdbcTemplate jdbc, String table, String column) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM information_schema.columns
                    WHERE table_schema = 'public' AND table_name = ? AND column_name = ?)
                """, Boolean.class, table, column));
    }

    private void assertDiaryAndReviewGuards(JdbcTemplate jdbc, UUID userId, UUID seriesId, UUID seasonId,
            UUID episodeEntryId, UUID writtenReviewId) {
        assertThatThrownBy(() -> insertDiaryEntry(jdbc, userId, seriesId, null, null))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> insertDiaryEntry(jdbc, userId, seasonId, null, null))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE diary_entries SET content_id = ? WHERE id = ?", seriesId, episodeEntryId))
                .isInstanceOf(DataAccessException.class);
        assertThat(jdbc.queryForObject("SELECT content_id FROM diary_entries WHERE id = ?", UUID.class, episodeEntryId))
                .isNotEqualTo(seriesId);
        assertThatThrownBy(() -> jdbc.update("UPDATE contents SET type = 'MOVIE' WHERE id = ?", seriesId))
                .isInstanceOf(DataAccessException.class);
        assertThat(jdbc.queryForObject("SELECT type FROM contents WHERE id = ?", String.class, seriesId)).isEqualTo("SERIES");
        assertThatThrownBy(() -> insertReview(jdbc, userId, seriesId, "SERIES", "LEGACY_ACTIVITY", "UNKNOWN", null, null))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> insertReview(jdbc, userId, seriesId, "SERIES", "WRITTEN_REVIEW", "LEGACY", 99, "invalid context"))
                .isInstanceOf(DataAccessException.class);
        assertThat(count(jdbc, "content_reviews", writtenReviewId)).isEqualTo(1);
    }
}
