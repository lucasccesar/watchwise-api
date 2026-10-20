package com.watchwise.watchwise_api.userlist.repository;

import com.watchwise.watchwise_api.userlist.entity.UserList;
import com.watchwise.watchwise_api.userlist.entity.UserListVisibility;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
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

    @Query("SELECT ul FROM UserList ul JOIN FETCH ul.user WHERE ul.id IN :ids")
    List<UserList> findByIdInWithUser(@Param("ids") Collection<UUID> ids);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT ul FROM UserList ul WHERE ul.id = :id")
    Optional<UserList> findByIdForUpdate(@Param("id") UUID id);

    Page<UserList> findByUserId(UUID userId, Pageable pageable);

    Page<UserList> findByUserIdAndVisibilityIn(UUID userId, Collection<UserListVisibility> visibilities, Pageable pageable);

    Page<UserList> findByVisibilityOrderByLikesCountDescCreatedAtDescIdDesc(
            UserListVisibility visibility, Pageable pageable);

    @Query(value = """
            SELECT ul FROM UserList ul JOIN FETCH ul.user
            WHERE ul.visibility = :visibility
              AND NOT EXISTS (SELECT b.id FROM UserBlock b
                              WHERE (b.blocker.id = :viewerId AND b.blocked.id = ul.user.id)
                                 OR (b.blocker.id = ul.user.id AND b.blocked.id = :viewerId))
            ORDER BY ul.likesCount DESC, ul.createdAt DESC, ul.id DESC
            """,
            countQuery = """
            SELECT COUNT(ul) FROM UserList ul
            WHERE ul.visibility = :visibility
              AND NOT EXISTS (SELECT b.id FROM UserBlock b
                              WHERE (b.blocker.id = :viewerId AND b.blocked.id = ul.user.id)
                                 OR (b.blocker.id = ul.user.id AND b.blocked.id = :viewerId))
            """)
    Page<UserList> findVisibleByVisibility(@Param("viewerId") UUID viewerId,
            @Param("visibility") UserListVisibility visibility, Pageable pageable);

    @Query("""
            SELECT ul.user.id AS userId, COUNT(ul.id) AS count
            FROM UserList ul
            WHERE ul.user.id IN :userIds
              AND NOT EXISTS (SELECT b.id FROM UserBlock b
                              WHERE (b.blocker.id = :viewerId AND b.blocked.id = ul.user.id)
                                 OR (b.blocker.id = ul.user.id AND b.blocked.id = :viewerId))
              AND (ul.user.id = :viewerId
                   OR ul.visibility = com.watchwise.watchwise_api.userlist.entity.UserListVisibility.PUBLIC
                   OR (ul.visibility = com.watchwise.watchwise_api.userlist.entity.UserListVisibility.FOLLOWERS
                       AND EXISTS (
                           SELECT f.id FROM Follower f
                           WHERE f.follower.id = :viewerId
                             AND f.followed.id = ul.user.id
                             AND f.status = com.watchwise.watchwise_api.follower.entity.FollowStatus.ACCEPTED
                       )))
            GROUP BY ul.user.id
            """)
    List<OwnerListCount> countListsVisibleToViewerByOwnerIdIn(
            @Param("viewerId") UUID viewerId, @Param("userIds") Collection<UUID> userIds);

    interface OwnerListCount {
        UUID getUserId();
        Long getCount();
    }

    @Query(
            value = """
            SELECT ul FROM UserList ul JOIN FETCH ul.user
            WHERE LOWER(ul.name) LIKE CONCAT('%', LOWER(:escapedName), '%') ESCAPE '\\'
            AND (
                ul.user.id = :viewerId
                OR ul.visibility = com.watchwise.watchwise_api.userlist.entity.UserListVisibility.PUBLIC
                OR (
                    ul.visibility = com.watchwise.watchwise_api.userlist.entity.UserListVisibility.FOLLOWERS
                    AND EXISTS (
                        SELECT 1 FROM Follower f
                        WHERE f.follower.id = :viewerId
                        AND f.followed.id = ul.user.id
                        AND f.status = com.watchwise.watchwise_api.follower.entity.FollowStatus.ACCEPTED
                    )
                )
            )
            AND NOT EXISTS (SELECT b.id FROM UserBlock b
                            WHERE (b.blocker.id = :viewerId AND b.blocked.id = ul.user.id)
                               OR (b.blocker.id = ul.user.id AND b.blocked.id = :viewerId))
            ORDER BY LOWER(ul.name) ASC, ul.id ASC
            """
    )
    Slice<UserList> findVisibleByNameContainingIgnoreCase(
            @Param("viewerId") UUID viewerId, @Param("escapedName") String escapedName, Pageable pageable);

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
              ul.created_at DESC,
              ul.id DESC
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
              ul.created_at DESC,
              ul.id DESC
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
