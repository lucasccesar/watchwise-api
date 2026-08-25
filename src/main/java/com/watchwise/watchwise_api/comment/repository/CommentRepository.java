package com.watchwise.watchwise_api.comment.repository;

import com.watchwise.watchwise_api.comment.entity.Comment;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface CommentRepository extends JpaRepository<Comment, UUID> {

    @Modifying
    @Query("UPDATE Comment c SET c.likesCount = c.likesCount + 1 WHERE c.id = :id")
    void incrementLikesCount(@Param("id") UUID id);

    @Modifying
    @Query("UPDATE Comment c SET c.likesCount = c.likesCount - 1 WHERE c.id = :id AND c.likesCount > 0")
    void decrementLikesCount(@Param("id") UUID id);

    @Query("SELECT c FROM Comment c JOIN FETCH c.user WHERE c.content.id = :contentId ORDER BY c.createdAt ASC")
    Page<Comment> findByContentIdOrderByCreatedAtAsc(@Param("contentId") UUID contentId, Pageable pageable);

    @Query("SELECT c FROM Comment c JOIN FETCH c.user WHERE c.list.id = :listId ORDER BY c.createdAt ASC")
    Page<Comment> findByListIdOrderByCreatedAtAsc(@Param("listId") UUID listId, Pageable pageable);

    @Query("SELECT c FROM Comment c JOIN FETCH c.user WHERE c.diaryEntry.id = :diaryEntryId ORDER BY c.createdAt ASC")
    Page<Comment> findByDiaryEntryIdOrderByCreatedAtAsc(@Param("diaryEntryId") UUID diaryEntryId, Pageable pageable);

    @Query("""
            SELECT c FROM Comment c
            LEFT JOIN FETCH c.list l LEFT JOIN FETCH l.user
            LEFT JOIN FETCH c.diaryEntry d LEFT JOIN FETCH d.user
            WHERE c.id = :id
            """)
    Optional<Comment> findByIdWithTargets(@Param("id") UUID id);
}