package com.watchwise.watchwise_api.like.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/** PostgreSQL operations sharing the account deletion's JPA transaction and connection. */
@RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY)
public class LikeAccountDeletionOperationsImpl implements LikeAccountDeletionOperations {
    private static final int BATCH_SIZE = 256;
    private static final String TARGET_COLUMNS = Arrays.stream(TargetType.values())
            .map(type -> type.column).collect(Collectors.joining(", "));
    private static final RowMapper<Target> TARGET_MAPPER = (row, index) -> readTarget(row);
    private final NamedParameterJdbcTemplate jdbc;

    @Override
    public List<Target> findSurvivingAccountLikeTargets(UUID actorId) {
        // Follow only ancestors of comments liked by this actor. A foreign-authored reply
        // also disappears when an ancestor or its social parent is deleted by the cascade.
        String survivorsQuery = """
                WITH RECURSIVE comment_ancestors AS (
                    SELECT c.id AS liked_comment_id, c.id, c.parent_comment_id, c.user_id,
                           c.list_id, c.diary_entry_id, c.dropped_entry_id, c.pick_id, c.content_review_id
                    FROM likes l JOIN comments c ON c.id = l.comment_id
                    WHERE l.user_id = :actorId
                    UNION
                    SELECT a.liked_comment_id, p.id, p.parent_comment_id, p.user_id,
                           p.list_id, p.diary_entry_id, p.dropped_entry_id, p.pick_id, p.content_review_id
                    FROM comment_ancestors a JOIN comments p ON p.id = a.parent_comment_id
                ), doomed_liked_comments AS (
                    SELECT DISTINCT liked_comment_id FROM comment_ancestors
                    WHERE user_id = :actorId
                       OR list_id IN (SELECT id FROM user_lists WHERE user_id = :actorId)
                       OR diary_entry_id IN (SELECT id FROM diary_entries WHERE user_id = :actorId)
                       OR dropped_entry_id IN (SELECT id FROM dropped_entries WHERE user_id = :actorId)
                       OR pick_id IN (SELECT id FROM picks WHERE user_id = :actorId)
                       OR content_review_id IN (SELECT id FROM content_reviews WHERE user_id = :actorId)
                )
                SELECT %s FROM likes l
                WHERE l.user_id = :actorId AND (
                    (l.comment_id IS NOT NULL AND NOT EXISTS (
                        SELECT 1 FROM doomed_liked_comments d WHERE d.liked_comment_id = l.comment_id))
                    OR l.diary_entry_id IN (SELECT id FROM diary_entries WHERE user_id <> :actorId)
                    OR l.dropped_entry_id IN (SELECT id FROM dropped_entries WHERE user_id <> :actorId)
                    OR l.list_id IN (SELECT id FROM user_lists WHERE user_id <> :actorId)
                    OR l.pick_id IN (SELECT id FROM picks WHERE user_id <> :actorId)
                    OR l.content_review_id IN (SELECT id FROM content_reviews WHERE user_id <> :actorId)
                    OR l.picks_template_id IS NOT NULL
                )
                """.formatted(TARGET_COLUMNS);
        // Templates always survive their creator (ON DELETE SET NULL).
        return jdbc.query(survivorsQuery, Map.of("actorId", actorId), TARGET_MAPPER);
    }

    @Override
    public void lockLikeTarget(TargetType type, UUID targetId) {
        lockTargets(type, List.of(targetId));
    }

    @Override
    public void lockAccountLikeTargets(List<Target> targets) {
        for (TargetType type : TargetType.values()) {
            List<UUID> ids = targets.stream().filter(target -> target.type() == type)
                    .map(Target::id).distinct().sorted(Comparator.comparing(UUID::toString)).toList();
            for (int start = 0; start < ids.size(); start += BATCH_SIZE) {
                lockTargets(type, ids.subList(start, Math.min(ids.size(), start + BATCH_SIZE)));
            }
        }
    }

    private void lockTargets(TargetType type, List<UUID> ids) {
        // A disappeared target is harmless: its FK cascade removed its likes as well.
        jdbc.queryForList("SELECT id FROM " + type.table + " WHERE id IN (:ids) ORDER BY id FOR UPDATE",
                Map.of("ids", ids), UUID.class);
    }

    @Override
    public void deleteAccountLikesAndReconcile(UUID actorId, List<Target> survivingTargets) {
        Set<Target> survivors = Set.copyOf(survivingTargets);
        List<Target> removed = jdbc.query("DELETE FROM likes WHERE user_id = :actorId RETURNING " + TARGET_COLUMNS,
                Map.of("actorId", actorId), TARGET_MAPPER).stream()
                .filter(survivors::contains).toList();
        for (TargetType type : TargetType.values()) {
            // ContentReview uses live counts, not a denormalized column.
            if (type == TargetType.CONTENT_REVIEW) continue;
            Map<UUID, Long> deltas = removed.stream().filter(target -> target.type() == type)
                    .collect(Collectors.groupingBy(Target::id, Collectors.counting()));
            List<Map.Entry<UUID, Long>> entries = new ArrayList<>(deltas.entrySet());
            entries.sort(Map.Entry.comparingByKey(Comparator.comparing(UUID::toString)));
            for (int start = 0; start < entries.size(); start += BATCH_SIZE) {
                decrement(type, entries.subList(start, Math.min(entries.size(), start + BATCH_SIZE)));
            }
        }
    }

    private void decrement(TargetType type, List<Map.Entry<UUID, Long>> deltas) {
        Map<String, Object> parameters = new HashMap<>();
        List<String> values = new ArrayList<>();
        for (int i = 0; i < deltas.size(); i++) {
            parameters.put("id" + i, deltas.get(i).getKey());
            parameters.put("delta" + i, deltas.get(i).getValue());
            values.add("(CAST(:id" + i + " AS uuid), CAST(:delta" + i + " AS bigint))");
        }
        int updated = jdbc.update("UPDATE " + type.table + " t SET likes_count = t.likes_count - d.delta "
                + "FROM (VALUES " + String.join(", ", values) + ") AS d(id, delta) "
                + "WHERE t.id = d.id AND t.likes_count >= d.delta", parameters);
        if (updated != deltas.size()) {
            throw new DataIntegrityViolationException("Like counter cannot cover account deletion delta: " + type);
        }
    }

    private static Target readTarget(ResultSet row) throws SQLException {
        for (TargetType type : TargetType.values()) {
            UUID id = row.getObject(type.column, UUID.class);
            if (id != null) return new Target(type, id);
        }
        throw new DataIntegrityViolationException("Like has no target");
    }
}
