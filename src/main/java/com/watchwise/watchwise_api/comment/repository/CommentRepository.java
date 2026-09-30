package com.watchwise.watchwise_api.comment.repository;

import com.watchwise.watchwise_api.comment.entity.Comment;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

public interface CommentRepository extends JpaRepository<Comment, UUID> {

    long countByListId(UUID listId);

    @Query("SELECT c.list.id AS listId, COUNT(c) AS count FROM Comment c WHERE c.list.id IN :listIds GROUP BY c.list.id")
    List<ListCommentCount> countByListIdIn(@Param("listIds") Collection<UUID> listIds);

    interface ListCommentCount {
        UUID getListId();
        long getCount();
    }

    long countByContentId(UUID contentId);

    @Query("SELECT c.content.id AS contentId, COUNT(c) AS count FROM Comment c WHERE c.content.id IN :contentIds GROUP BY c.content.id")
    List<ContentCommentCount> countByContentIdIn(@Param("contentIds") Collection<UUID> contentIds);

    interface ContentCommentCount {
        UUID getContentId();
        long getCount();
    }

    @Query("SELECT c.diaryEntry.id AS diaryEntryId, COUNT(c) AS count FROM Comment c WHERE c.diaryEntry.id IN :diaryEntryIds GROUP BY c.diaryEntry.id")
    List<DiaryCommentCount> countByDiaryEntryIdIn(@Param("diaryEntryIds") Collection<UUID> diaryEntryIds);

    interface DiaryCommentCount {
        UUID getDiaryEntryId();
        long getCount();
    }

    @Query("SELECT c.droppedEntry.id AS droppedEntryId, COUNT(c) AS count FROM Comment c WHERE c.droppedEntry.id IN :droppedEntryIds GROUP BY c.droppedEntry.id")
    List<DroppedCommentCount> countByDroppedEntryIdIn(@Param("droppedEntryIds") Collection<UUID> droppedEntryIds);

    interface DroppedCommentCount {
        UUID getDroppedEntryId();
        long getCount();
    }

    @Query("SELECT c.pick.id AS pickId, COUNT(c) AS count FROM Comment c WHERE c.pick.id IN :pickIds GROUP BY c.pick.id")
    List<PickCommentCount> countByPickIdIn(@Param("pickIds") Collection<UUID> pickIds);

    interface PickCommentCount {
        UUID getPickId();
        long getCount();
    }

    @Query("SELECT c.picksTemplate.id AS templateId, COUNT(c) AS count FROM Comment c WHERE c.picksTemplate.id IN :templateIds GROUP BY c.picksTemplate.id")
    List<TemplateCommentCount> countByPicksTemplateIdIn(@Param("templateIds") Collection<UUID> templateIds);

    interface TemplateCommentCount {
        UUID getTemplateId();
        long getCount();
    }

    @Query(value = """
            SELECT ranked.id
            FROM (
                SELECT c.*, ROW_NUMBER() OVER (
                    PARTITION BY c.diary_entry_id
                    ORDER BY c.created_at DESC, c.id DESC
                ) AS row_number
                FROM comments c
                WHERE c.diary_entry_id IN (:diaryEntryIds)
            ) ranked
            WHERE ranked.row_number <= 3
            ORDER BY ranked.diary_entry_id ASC, ranked.created_at DESC, ranked.id DESC
            """, nativeQuery = true)
    List<UUID> findRecentIdsByDiaryEntryIdIn(@Param("diaryEntryIds") Collection<UUID> diaryEntryIds);

    default List<Comment> findRecentByDiaryEntryIdIn(Collection<UUID> diaryEntryIds) {
        return findRecentCommentsByIds(findRecentIdsByDiaryEntryIdIn(diaryEntryIds));
    }

    @Query(value = """
            SELECT ranked.id
            FROM (
                SELECT c.*, ROW_NUMBER() OVER (
                    PARTITION BY c.dropped_entry_id
                    ORDER BY c.created_at DESC, c.id DESC
                ) AS row_number
                FROM comments c
                WHERE c.dropped_entry_id IN (:droppedEntryIds)
            ) ranked
            WHERE ranked.row_number <= 3
            ORDER BY ranked.dropped_entry_id ASC, ranked.created_at DESC, ranked.id DESC
            """, nativeQuery = true)
    List<UUID> findRecentIdsByDroppedEntryIdIn(@Param("droppedEntryIds") Collection<UUID> droppedEntryIds);

    default List<Comment> findRecentByDroppedEntryIdIn(Collection<UUID> droppedEntryIds) {
        return findRecentCommentsByIds(findRecentIdsByDroppedEntryIdIn(droppedEntryIds));
    }

    @Query(value = """
            SELECT ranked.id
            FROM (
                SELECT c.*, ROW_NUMBER() OVER (
                    PARTITION BY c.pick_id
                    ORDER BY c.created_at DESC, c.id DESC
                ) AS row_number
                FROM comments c
                WHERE c.pick_id IN (:pickIds)
            ) ranked
            WHERE ranked.row_number <= 3
            ORDER BY ranked.pick_id ASC, ranked.created_at DESC, ranked.id DESC
            """, nativeQuery = true)
    List<UUID> findRecentIdsByPickIdIn(@Param("pickIds") Collection<UUID> pickIds);

    default List<Comment> findRecentByPickIdIn(Collection<UUID> pickIds) {
        return findRecentCommentsByIds(findRecentIdsByPickIdIn(pickIds));
    }

    @Query(value = """
            SELECT ranked.id
            FROM (
                SELECT c.*, ROW_NUMBER() OVER (
                    PARTITION BY c.picks_template_id
                    ORDER BY c.created_at DESC, c.id DESC
                ) AS row_number
                FROM comments c
                WHERE c.picks_template_id IN (:templateIds)
            ) ranked
            WHERE ranked.row_number <= 3
            ORDER BY ranked.picks_template_id ASC, ranked.created_at DESC, ranked.id DESC
            """, nativeQuery = true)
    List<UUID> findRecentIdsByPicksTemplateIdIn(@Param("templateIds") Collection<UUID> templateIds);

    default List<Comment> findRecentByPicksTemplateIdIn(Collection<UUID> templateIds) {
        return findRecentCommentsByIds(findRecentIdsByPicksTemplateIdIn(templateIds));
    }

    @Query("SELECT c FROM Comment c JOIN FETCH c.user WHERE c.id IN :commentIds")
    List<Comment> findByIdInWithUser(@Param("commentIds") Collection<UUID> commentIds);

    private List<Comment> findRecentCommentsByIds(List<UUID> commentIds) {
        if (commentIds.isEmpty()) {
            return List.of();
        }

        Map<UUID, Comment> commentsById = findByIdInWithUser(commentIds).stream()
                .collect(Collectors.toMap(Comment::getId, Function.identity()));

        return commentIds.stream().map(commentsById::get).toList();
    }

    @Modifying
    @Query("UPDATE Comment c SET c.likesCount = c.likesCount + 1 WHERE c.id = :id")
    void incrementLikesCount(@Param("id") UUID id);

    @Modifying
    @Query("UPDATE Comment c SET c.likesCount = c.likesCount - 1 WHERE c.id = :id AND c.likesCount > 0")
    void decrementLikesCount(@Param("id") UUID id);

    @Query("SELECT c FROM Comment c JOIN FETCH c.user WHERE c.content.id = :contentId ORDER BY c.createdAt ASC, c.id ASC")
    Page<Comment> findByContentIdOrderByCreatedAtAsc(@Param("contentId") UUID contentId, Pageable pageable);

    @Query("SELECT c FROM Comment c JOIN FETCH c.user WHERE c.list.id = :listId ORDER BY c.createdAt ASC, c.id ASC")
    Page<Comment> findByListIdOrderByCreatedAtAsc(@Param("listId") UUID listId, Pageable pageable);

    @Query("SELECT c FROM Comment c JOIN FETCH c.user WHERE c.diaryEntry.id = :diaryEntryId ORDER BY c.createdAt ASC, c.id ASC")
    Page<Comment> findByDiaryEntryIdOrderByCreatedAtAsc(@Param("diaryEntryId") UUID diaryEntryId, Pageable pageable);

    @Query("SELECT c FROM Comment c JOIN FETCH c.user WHERE c.droppedEntry.id = :droppedEntryId ORDER BY c.createdAt ASC, c.id ASC")
    Page<Comment> findByDroppedEntryIdOrderByCreatedAtAsc(@Param("droppedEntryId") UUID droppedEntryId, Pageable pageable);

    @Query("SELECT c FROM Comment c JOIN FETCH c.user WHERE c.pick.id = :pickId ORDER BY c.createdAt ASC, c.id ASC")
    Page<Comment> findByPickIdOrderByCreatedAtAsc(@Param("pickId") UUID pickId, Pageable pageable);

    @Query("SELECT c FROM Comment c JOIN FETCH c.user WHERE c.picksTemplate.id = :templateId ORDER BY c.createdAt ASC, c.id ASC")
    Page<Comment> findByPicksTemplateIdOrderByCreatedAtAsc(@Param("templateId") UUID templateId, Pageable pageable);

    @Query("""
            SELECT c FROM Comment c JOIN FETCH c.user
            LEFT JOIN FETCH c.list l LEFT JOIN FETCH l.user
            LEFT JOIN FETCH c.diaryEntry d LEFT JOIN FETCH d.user
            LEFT JOIN FETCH c.droppedEntry de LEFT JOIN FETCH de.user
            LEFT JOIN FETCH c.pick p LEFT JOIN FETCH p.user
            LEFT JOIN FETCH c.picksTemplate t
            WHERE c.id = :id
            """)
    Optional<Comment> findByIdWithTargets(@Param("id") UUID id);
}
