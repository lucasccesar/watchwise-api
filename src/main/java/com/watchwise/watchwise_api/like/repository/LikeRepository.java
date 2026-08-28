package com.watchwise.watchwise_api.like.repository;

import com.watchwise.watchwise_api.like.entity.Like;
import com.watchwise.watchwise_api.userlist.entity.UserList;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.Set;
import java.util.UUID;

public interface LikeRepository extends JpaRepository<Like, UUID> {

    @Modifying
    @Query("DELETE FROM Like l WHERE l.user.id = :userId AND l.comment.id = :commentId")
    int deleteByUserIdAndCommentId(@Param("userId") UUID userId, @Param("commentId") UUID commentId);

    @Modifying
    @Query("DELETE FROM Like l WHERE l.user.id = :userId AND l.diaryEntry.id = :diaryEntryId")
    int deleteByUserIdAndDiaryEntryId(@Param("userId") UUID userId, @Param("diaryEntryId") UUID diaryEntryId);

    @Modifying
    @Query("DELETE FROM Like l WHERE l.user.id = :userId AND l.list.id = :listId")
    int deleteByUserIdAndListId(@Param("userId") UUID userId, @Param("listId") UUID listId);

    boolean existsByUserIdAndCommentId(UUID userId, UUID commentId);

    boolean existsByUserIdAndDiaryEntryId(UUID userId, UUID diaryEntryId);

    boolean existsByUserIdAndListId(UUID userId, UUID listId);

    @Query("SELECT l.comment.id FROM Like l WHERE l.user.id = :userId AND l.comment.id IN :commentIds")
    Set<UUID> findLikedCommentIds(@Param("userId") UUID userId, @Param("commentIds") Collection<UUID> commentIds);

    @Query("SELECT l.diaryEntry.id FROM Like l WHERE l.user.id = :userId AND l.diaryEntry.id IN :diaryEntryIds")
    Set<UUID> findLikedDiaryEntryIds(@Param("userId") UUID userId, @Param("diaryEntryIds") Collection<UUID> diaryEntryIds);

    @Query("SELECT l.list.id FROM Like l WHERE l.user.id = :userId AND l.list.id IN :listIds")
    Set<UUID> findLikedListIds(@Param("userId") UUID userId, @Param("listIds") Collection<UUID> listIds);

    @Query("SELECT l.list FROM Like l WHERE l.user.id = :userId AND l.list IS NOT NULL ORDER BY l.createdAt DESC")
    Page<UserList> findLikedListsByUserId(@Param("userId") UUID userId, Pageable pageable);

}
