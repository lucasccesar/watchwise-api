package com.watchwise.watchwise_api.userlist.repository;

import com.watchwise.watchwise_api.userlist.entity.UserList;
import com.watchwise.watchwise_api.userlist.entity.UserListVisibility;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UserListRepository extends JpaRepository<UserList, UUID> {

    List<UserList> findByUserId(UUID userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT ul FROM UserList ul WHERE ul.id = :id")
    Optional<UserList> findByIdForUpdate(@Param("id") UUID id);

    Page<UserList> findByUserId(UUID userId, Pageable pageable);

    Page<UserList> findByUserIdAndVisibilityIn(UUID userId, Collection<UserListVisibility> visibilities, Pageable pageable);

    @Modifying
    @Query("UPDATE UserList u SET u.likesCount = u.likesCount + 1 WHERE u.id = :id")
    void incrementLikesCount(@Param("id") UUID id);

    @Modifying
    @Query("UPDATE UserList u SET u.likesCount = u.likesCount - 1 WHERE u.id = :id AND u.likesCount > 0")
    void decrementLikesCount(@Param("id") UUID id);

    long countByUserIdAndRankIsNotNull(UUID userId);

    @Modifying
    @Query("UPDATE UserList ul SET ul.rank = ul.rank + :offset WHERE ul.user.id = :userId AND ul.rank >= :rangeStart AND ul.rank <= :rangeEnd")
    void parkRanksInRange(
            @Param("userId") UUID userId,
            @Param("rangeStart") int rangeStart, @Param("rangeEnd") int rangeEnd, @Param("offset") int offset);

    @Modifying
    @Query("UPDATE UserList ul SET ul.rank = ul.rank - :offset + :delta WHERE ul.user.id = :userId AND ul.rank > :offset")
    void settleParkedRanks(
            @Param("userId") UUID userId,
            @Param("offset") int offset, @Param("delta") int delta);

    @Query(value = """
            SELECT ul.* FROM user_lists ul
            LEFT JOIN user_list_items uli ON uli.user_list_id = ul.id
            WHERE ul.user_id = :userId
            AND ul.visibility IN (:visibilities)
            GROUP BY ul.id
            ORDER BY
              CASE WHEN :sortDirection = 'ASC' THEN COUNT(uli.id) END ASC,
              CASE WHEN :sortDirection = 'DESC' THEN COUNT(uli.id) END DESC,
              ul.created_at DESC
            """,
            countQuery = """
            SELECT COUNT(*) FROM user_lists ul
            WHERE ul.user_id = :userId
            AND ul.visibility IN (:visibilities)
            """,
            nativeQuery = true)
    Page<UserList> findByUserIdOrderByItemsCount(
            @Param("userId") UUID userId, @Param("visibilities") List<String> visibilities,
            @Param("sortDirection") String sortDirection, Pageable pageable);

    @Query(value = """
            SELECT ul.* FROM user_lists ul
            LEFT JOIN comments c ON c.list_id = ul.id
            WHERE ul.user_id = :userId
            AND ul.visibility IN (:visibilities)
            GROUP BY ul.id
            ORDER BY
              CASE WHEN :sortDirection = 'ASC' THEN COUNT(c.id) END ASC,
              CASE WHEN :sortDirection = 'DESC' THEN COUNT(c.id) END DESC,
              ul.created_at DESC
            """,
            countQuery = """
            SELECT COUNT(*) FROM user_lists ul
            WHERE ul.user_id = :userId
            AND ul.visibility IN (:visibilities)
            """,
            nativeQuery = true)
    Page<UserList> findByUserIdOrderByCommentsCount(
            @Param("userId") UUID userId, @Param("visibilities") List<String> visibilities,
            @Param("sortDirection") String sortDirection, Pageable pageable);

}