package com.watchwise.watchwise_api.like.repository;

import java.util.List;
import java.util.UUID;

public interface LikeAccountDeletionOperations {
    enum TargetType {
        // Lists precede every other social target to preserve the C06 structure order.
        LIST("user_lists", "list_id"), COMMENT("comments", "comment_id"),
        DIARY_ENTRY("diary_entries", "diary_entry_id"), DROPPED_ENTRY("dropped_entries", "dropped_entry_id"),
        PICK("picks", "pick_id"), PICKS_TEMPLATE("picks_templates", "picks_template_id"),
        CONTENT_REVIEW("content_reviews", "content_review_id");

        final String table;
        final String column;
        TargetType(String table, String column) { this.table = table; this.column = column; }
    }

    record Target(TargetType type, UUID id) {}

    List<Target> findAccountLikeTargets(UUID actorId);
    void lockLikeTarget(TargetType type, UUID targetId);
    void lockAccountLikeTargets(List<Target> targets);
    void deleteAccountLikesAndReconcile(UUID actorId);
}
