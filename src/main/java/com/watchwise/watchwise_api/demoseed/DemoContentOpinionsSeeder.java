package com.watchwise.watchwise_api.demoseed;

import com.watchwise.watchwise_api.calendar.entity.CalendarScheduleCompleteness;
import com.watchwise.watchwise_api.comment.entity.Comment;
import com.watchwise.watchwise_api.comment.repository.CommentRepository;
import com.watchwise.watchwise_api.common.exception.TmdbUnavailableException;
import com.watchwise.watchwise_api.common.transaction.UserContentMutationLock;
import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.contentrating.entity.ContentRating;
import com.watchwise.watchwise_api.contentrating.repository.ContentRatingRepository;
import com.watchwise.watchwise_api.contentreview.entity.ContentReview;
import com.watchwise.watchwise_api.contentreview.entity.ReviewContextKind;
import com.watchwise.watchwise_api.contentreview.entity.ReviewPublicationKind;
import com.watchwise.watchwise_api.contentreview.repository.ContentReviewRepository;
import com.watchwise.watchwise_api.diarycompletion.service.DiaryCompletionService;
import com.watchwise.watchwise_api.like.entity.Like;
import com.watchwise.watchwise_api.like.repository.LikeRepository;
import com.watchwise.watchwise_api.user.entity.User;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.sql.Array;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "app.demo-opinion-seed", name = "enabled", havingValue = "true")
public class DemoContentOpinionsSeeder implements ApplicationRunner {

    private static final String DEMO_USERNAME_PREFIX = "watchwise_demo_20261106_";
    private static final int COMMENT_BATCH_SIZE = 500;
    private static final int LIKE_BATCH_SIZE = 10_000;
    private static final int REVIEW_PERCENTAGE = 60;
    private static final String[] REVIEW_TEXTS = {
            "Gostei da jornada da série e de como os personagens mudam ao longo das temporadas.",
            "Uma temporada muito bem construída, com bons episódios do começo ao fim.",
            "A história encontrou um ritmo ótimo e terminou esta parte de um jeito marcante.",
            "Curti bastante o desenvolvimento dos personagens e a direção desta temporada.",
            "Foi uma ótima maratona; vários episódios ficaram na memória.",
            "A série tem altos e baixos, mas no conjunto valeu acompanhar cada episódio.",
            "Gostei do clima e dos temas que a temporada desenvolveu.",
            "Uma conclusão satisfatória para esta etapa da história."
    };
    private static final String[] COMMENT_TEXTS = {
            "Também gostei dessa fase da série.",
            "Essa temporada tem episódios muito bons.",
            "Boa review, me lembrou de rever a série.",
            "Concordo, o desenvolvimento funciona muito bem.",
            "Essa história rende uma ótima maratona."
    };

    private final JdbcTemplate jdbcTemplate;
    private final ContentRatingRepository contentRatingRepository;
    private final ContentReviewRepository contentReviewRepository;
    private final DiaryCompletionService diaryCompletionService;
    private final UserContentMutationLock userContentMutationLock;
    private final CommentRepository commentRepository;
    private final LikeRepository likeRepository;
    private final EntityManager entityManager;
    private final PlatformTransactionManager transactionManager;
    private final ObjectMapper objectMapper;

    private final Random random = new Random(20261107L);

    @Override
    public void run(ApplicationArguments args) {
        List<UUID> userIds = loadDemoUsers();
        if (userIds.size() != 100) {
            throw new IllegalStateException("Expected exactly 100 demo users before seeding opinions");
        }

        Set<OpinionKey> existingRatings = loadExistingOpinionKeys("content_ratings", "");
        Set<OpinionKey> existingReviews = loadExistingOpinionKeys(
                "content_reviews", "AND opinion.publication_kind = 'WRITTEN_REVIEW'");
        int ratingsCreated = 0;
        int reviewsCreated = 0;
        int eligibleTargets = 0;
        int unavailableUsers = 0;
        for (int index = 0; index < userIds.size(); index++) {
            UUID userId = userIds.get(index);
            try {
                diaryCompletionService.refreshStaleScopes(userId, Instant.now());
            } catch (TmdbUnavailableException unavailable) {
                unavailableUsers++;
                log.info("Completion schedule unavailable for demo user {}", index + 1);
            }

            OpinionCounts counts = saveUserOpinions(userId, Instant.now(), existingRatings, existingReviews);
            ratingsCreated += counts.ratingsCreated();
            reviewsCreated += counts.reviewsCreated();
            eligibleTargets += counts.eligibleTargets();

            if ((index + 1) % 10 == 0) {
                log.info("Checked completed series/season opinions for {}/{} demo users", index + 1, userIds.size());
            }
        }

        List<ReviewTarget> reviewTargets = loadReviewsWithoutSocialData();
        long[] interactionCounts = createReviewCommentsAndLikes(userIds, reviewTargets);
        log.info("Separate opinion seed complete: {} eligible targets, {} ratings, {} written reviews, " +
                        "{} review comments, {} likes; {} users had an unavailable schedule",
                eligibleTargets, ratingsCreated, reviewsCreated, interactionCounts[0], interactionCounts[1],
                unavailableUsers);
    }

    private List<UUID> loadDemoUsers() {
        return jdbcTemplate.query(
                "SELECT id FROM users WHERE position(? in username) = 1 ORDER BY username",
                (resultSet, rowNumber) -> resultSet.getObject("id", UUID.class),
                DEMO_USERNAME_PREFIX);
    }

    private Set<OpinionKey> loadExistingOpinionKeys(String table, String filter) {
        String sql = "SELECT opinion.user_id, opinion.content_id FROM " + table +
                " opinion JOIN users owner ON owner.id = opinion.user_id " +
                "WHERE position(? in owner.username) = 1 " + filter;
        return new HashSet<>(jdbcTemplate.query(sql, (resultSet, rowNumber) -> new OpinionKey(
                resultSet.getObject("user_id", UUID.class),
                resultSet.getObject("content_id", UUID.class)), DEMO_USERNAME_PREFIX));
    }

    private OpinionCounts saveUserOpinions(
            UUID userId,
            Instant now,
            Set<OpinionKey> existingRatings,
            Set<OpinionKey> existingReviews) {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        return transaction.execute(status -> {
            userContentMutationLock.lock(userId);
            List<EligibleTarget> eligibleTargets = loadEligibleTargets(userId, now);
            List<ContentRating> newRatings = new ArrayList<>();
            List<ContentReview> newReviews = new ArrayList<>();

            for (EligibleTarget target : eligibleTargets) {
                OpinionKey key = new OpinionKey(userId, target.contentId());
                if (!existingRatings.contains(key)) {
                    newRatings.add(ContentRating.builder()
                            .userId(userId)
                            .contentId(target.contentId())
                            .contentType(target.contentType())
                            .score(random.nextInt(10) + 1)
                            .createdAt(now)
                            .updatedAt(now)
                            .build());
                    existingRatings.add(key);
                }

                if (existingReviews.contains(key) || random.nextInt(100) >= REVIEW_PERCENTAGE) continue;
                try {
                    String context = objectMapper.writeValueAsString(new CompletionContext(
                            target.contentType().name(),
                            target.tmdbId(),
                            target.seriesTmdbId(),
                            target.seasonNumber(),
                            target.watchNumber(),
                            target.historyRevision(),
                            target.scheduleVersion(),
                            target.eventIds()));
                    newReviews.add(ContentReview.builder()
                            .userId(userId)
                            .contentId(target.contentId())
                            .contentType(target.contentType())
                            .publicationKind(ReviewPublicationKind.WRITTEN_REVIEW)
                            .contextKind(ReviewContextKind.COMPLETION)
                            .contextJson(context)
                            .comment(REVIEW_TEXTS[random.nextInt(REVIEW_TEXTS.length)])
                            .scoreAtPublication(null)
                            .watchNumber(target.watchNumber())
                            .sourcePublishedAt(now)
                            .createdAt(now)
                            .updatedAt(now)
                            .build());
                    existingReviews.add(key);
                } catch (RuntimeException serializationFailure) {
                    throw new IllegalStateException("Could not serialize demo review completion context",
                            serializationFailure);
                }
            }

            if (!newRatings.isEmpty()) contentRatingRepository.saveAllAndFlush(newRatings);
            if (!newReviews.isEmpty()) contentReviewRepository.saveAllAndFlush(newReviews);
            entityManager.clear();
            return new OpinionCounts(newRatings.size(), newReviews.size(), eligibleTargets.size());
        });
    }

    private List<EligibleTarget> loadEligibleTargets(UUID userId, Instant now) {
        String sql = """
                SELECT p.content_id,
                       content.type AS content_type,
                       content.tmdb_id,
                       content.series_tmdb_id,
                       content.season_number,
                       p.watch_number,
                       p.history_revision,
                       p.schedule_version,
                       array_agg(episode_entry.id ORDER BY episode_entry.id) AS event_ids
                FROM diary_completion_projections p
                JOIN contents content ON content.id = p.content_id
                JOIN users owner ON owner.id = p.user_id
                JOIN user_series_history_states history
                  ON history.user_id = p.user_id
                 AND history.series_tmdb_id = COALESCE(content.series_tmdb_id, content.tmdb_id)
                LEFT JOIN calendar_schedule_completeness marker
                  ON marker.group_type = content.type
                 AND marker.series_tmdb_id = COALESCE(content.series_tmdb_id, content.tmdb_id)
                 AND marker.season_number IS NOT DISTINCT FROM
                     CASE WHEN content.type = 'SEASON' THEN content.season_number ELSE NULL END
                 AND marker.region = owner.preferred_region
                 AND marker.language = owner.preferred_language
                JOIN diary_entries episode_entry
                  ON episode_entry.user_id = p.user_id
                 AND episode_entry.watch_number = p.watch_number
                JOIN contents episode
                  ON episode.id = episode_entry.content_id
                 AND episode.type = 'EPISODE'
                 AND episode.series_tmdb_id = COALESCE(content.series_tmdb_id, content.tmdb_id)
                 AND (content.type = 'SERIES' OR episode.season_number = content.season_number)
                WHERE p.user_id = ?
                  AND content.type IN ('SERIES', 'SEASON')
                  AND p.valid_until > ?
                  AND p.history_revision = history.history_revision
                  AND history.computed_revision = history.history_revision
                  AND p.schedule_version = COALESCE(marker.scope_version, 0)
                  AND NOT EXISTS (
                      SELECT 1
                      FROM diary_completion_projections newer
                      WHERE newer.user_id = p.user_id
                        AND newer.content_id = p.content_id
                        AND newer.watch_number > p.watch_number
                        AND newer.valid_until > ?
                        AND newer.history_revision = history.history_revision
                        AND newer.schedule_version = COALESCE(marker.scope_version, 0)
                  )
                GROUP BY p.content_id, content.type, content.tmdb_id, content.series_tmdb_id,
                         content.season_number, p.watch_number, p.history_revision, p.schedule_version
                ORDER BY content.type, content.tmdb_id, content.series_tmdb_id, content.season_number
                """;
        Timestamp cutoff = Timestamp.from(now);
        return jdbcTemplate.query(sql, (resultSet, rowNumber) -> new EligibleTarget(
                resultSet.getObject("content_id", UUID.class),
                ContentType.valueOf(resultSet.getString("content_type")),
                resultSet.getString("tmdb_id"),
                resultSet.getString("series_tmdb_id"),
                nullableInteger(resultSet, "season_number"),
                resultSet.getInt("watch_number"),
                resultSet.getLong("history_revision"),
                resultSet.getLong("schedule_version"),
                readUuidArray(resultSet.getArray("event_ids"))),
                userId, cutoff, cutoff);
    }

    private Integer nullableInteger(java.sql.ResultSet resultSet, String column) throws SQLException {
        int value = resultSet.getInt(column);
        return resultSet.wasNull() ? null : value;
    }

    private List<UUID> readUuidArray(Array sqlArray) throws SQLException {
        Object[] values = (Object[]) sqlArray.getArray();
        List<UUID> ids = new ArrayList<>(values.length);
        for (Object value : values) {
            ids.add(value instanceof UUID id ? id : UUID.fromString(value.toString()));
        }
        sqlArray.free();
        ids.sort(Comparator.naturalOrder());
        return List.copyOf(ids);
    }

    private List<ReviewTarget> loadReviewsWithoutSocialData() {
        String sql = """
                SELECT review.id, review.user_id
                FROM content_reviews review
                JOIN users owner ON owner.id = review.user_id
                WHERE position(? in owner.username) = 1
                  AND review.publication_kind = 'WRITTEN_REVIEW'
                  AND NOT EXISTS (SELECT 1 FROM comments c WHERE c.content_review_id = review.id)
                  AND NOT EXISTS (SELECT 1 FROM likes l WHERE l.content_review_id = review.id)
                ORDER BY review.created_at, review.id
                """;
        return jdbcTemplate.query(sql, (resultSet, rowNumber) -> new ReviewTarget(
                resultSet.getObject("id", UUID.class),
                resultSet.getObject("user_id", UUID.class)), DEMO_USERNAME_PREFIX);
    }

    private long[] createReviewCommentsAndLikes(List<UUID> userIds, List<ReviewTarget> reviews) {
        if (reviews.isEmpty()) return new long[]{0, 0};
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.setTimeout(600);
        long[] counts = transaction.execute(status -> {
            List<Like> likeBatch = new ArrayList<>(LIKE_BATCH_SIZE);
            List<CommentPlan> commentBatch = new ArrayList<>(COMMENT_BATCH_SIZE);
            long commentsCreated = 0;
            long likesCreated = 0;
            LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);

            for (ReviewTarget review : reviews) {
                int reviewLikes = random.nextInt(11);
                likesCreated += appendLikes(likeBatch, userIds, review.ownerId(), review.id(), reviewLikes, true, now);
                flushLikesIfFull(likeBatch);

                int commentCount = random.nextInt(6);
                for (int index = 0; index < commentCount; index++) {
                    UUID authorId = randomUserExcept(userIds, review.ownerId());
                    int commentLikes = random.nextInt(11);
                    Comment comment = Comment.builder()
                            .user(entityManager.getReference(User.class, authorId))
                            .contentReview(entityManager.getReference(ContentReview.class, review.id()))
                            .text(COMMENT_TEXTS[random.nextInt(COMMENT_TEXTS.length)])
                            .containsSpoiler(false)
                            .parentComment(null)
                            .createdAt(now)
                            .updatedAt(now)
                            .likesCount(commentLikes)
                            .build();
                    commentBatch.add(new CommentPlan(comment, authorId, commentLikes));
                    if (commentBatch.size() >= COMMENT_BATCH_SIZE) {
                        CommentBatchResult result = flushComments(commentBatch, likeBatch, userIds, now);
                        commentsCreated += result.commentsCreated();
                        likesCreated += result.likesCreated();
                    }
                }
            }

            if (!commentBatch.isEmpty()) {
                CommentBatchResult result = flushComments(commentBatch, likeBatch, userIds, now);
                commentsCreated += result.commentsCreated();
                likesCreated += result.likesCreated();
            }
            if (!likeBatch.isEmpty()) {
                likeRepository.saveAllAndFlush(likeBatch);
                likesCreated += likeBatch.size();
                likeBatch.clear();
            }
            entityManager.clear();
            return new long[]{commentsCreated, likesCreated};
        });
        return counts == null ? new long[]{0, 0} : counts;
    }

    private CommentBatchResult flushComments(
            List<CommentPlan> batch,
            List<Like> likeBatch,
            List<UUID> userIds,
            LocalDateTime now) {
        List<Comment> saved = commentRepository.saveAllAndFlush(batch.stream().map(CommentPlan::comment).toList());
        long likesCreated = 0;
        for (int index = 0; index < saved.size(); index++) {
            CommentPlan plan = batch.get(index);
            likesCreated += appendLikes(likeBatch, userIds, plan.authorId(), saved.get(index).getId(),
                    plan.likesCount(), false, now);
            flushLikesIfFull(likeBatch);
        }
        int commentsCreated = saved.size();
        batch.clear();
        entityManager.clear();
        return new CommentBatchResult(commentsCreated, likesCreated);
    }

    private long appendLikes(
            List<Like> batch,
            List<UUID> users,
            UUID ownerId,
            UUID targetId,
            int count,
            boolean contentReviewTarget,
            LocalDateTime now) {
        if (count == 0) return 0;
        List<UUID> eligible = new ArrayList<>(users);
        eligible.remove(ownerId);
        Collections.shuffle(eligible, random);
        int likeCount = Math.min(count, eligible.size());
        for (int index = 0; index < likeCount; index++) {
            Like.LikeBuilder builder = Like.builder()
                    .user(entityManager.getReference(User.class, eligible.get(index)))
                    .createdAt(now);
            if (contentReviewTarget) {
                builder.contentReview(entityManager.getReference(ContentReview.class, targetId));
            } else {
                builder.comment(entityManager.getReference(Comment.class, targetId));
            }
            batch.add(builder.build());
            if (batch.size() >= LIKE_BATCH_SIZE) flushLikesIfFull(batch);
        }
        return likeCount;
    }

    private void flushLikesIfFull(List<Like> batch) {
        if (batch.size() < LIKE_BATCH_SIZE) return;
        likeRepository.saveAllAndFlush(batch);
        batch.clear();
        entityManager.clear();
    }

    private UUID randomUserExcept(List<UUID> userIds, UUID ownerId) {
        List<UUID> eligible = new ArrayList<>(userIds);
        eligible.remove(ownerId);
        return eligible.get(random.nextInt(eligible.size()));
    }

    private record OpinionKey(UUID userId, UUID contentId) {}
    private record EligibleTarget(
            UUID contentId,
            ContentType contentType,
            String tmdbId,
            String seriesTmdbId,
            Integer seasonNumber,
            int watchNumber,
            long historyRevision,
            long scheduleVersion,
            List<UUID> eventIds) {}
    private record ReviewTarget(UUID id, UUID ownerId) {}
    private record CommentPlan(Comment comment, UUID authorId, int likesCount) {}
    private record OpinionCounts(int ratingsCreated, int reviewsCreated, int eligibleTargets) {}
    private record CommentBatchResult(long commentsCreated, long likesCreated) {}
    private record CompletionContext(
            String contentType,
            String tmdbId,
            String seriesTmdbId,
            Integer seasonNumber,
            Integer watchNumber,
            long historyRevision,
            long scheduleVersion,
            List<UUID> eventIds) {}
}
