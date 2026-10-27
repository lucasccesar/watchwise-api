package com.watchwise.watchwise_api.userlist.repository;

import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.userlist.entity.UserList;
import com.watchwise.watchwise_api.userlist.entity.UserListItem;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public interface UserListItemRepository extends JpaRepository<UserListItem, UUID> {

    @Query("""
            SELECT DISTINCT child FROM UserList child JOIN FETCH child.user
            WHERE child.id IN :childListIds
            AND NOT EXISTS (SELECT b.id FROM UserBlock b WHERE
                (b.blocker.id = :viewerId AND b.blocked.id = child.user.id)
                OR (b.blocker.id = child.user.id AND b.blocked.id = :viewerId))
            AND (child.user.id = :viewerId
                OR child.visibility = com.watchwise.watchwise_api.userlist.entity.UserListVisibility.PUBLIC
                OR (child.visibility = com.watchwise.watchwise_api.userlist.entity.UserListVisibility.FOLLOWERS
                    AND EXISTS (
                        SELECT 1 FROM Follower follower
                        WHERE follower.follower.id = :viewerId
                        AND follower.followed.id = child.user.id
                        AND follower.status = com.watchwise.watchwise_api.follower.entity.FollowStatus.ACCEPTED
                    )))
            """)
    List<UserList> findVisibleChildListsByIdIn(
            @Param("childListIds") Collection<UUID> childListIds, @Param("viewerId") UUID viewerId);

    @Query(value = """
            SELECT parent_id AS parentId, child_id AS childId, owner_id AS ownerId,
                   username AS username, owner_name AS ownerName, profile_picture AS profilePicture,
                   is_profile_public AS profilePublic, child_name AS childName, visibility AS visibility
            FROM (
                SELECT item.user_list_id AS parent_id, child.id AS child_id, owner.id AS owner_id,
                       owner.username, owner.name AS owner_name, owner.profile_picture,
                       owner.is_profile_public, child.name AS child_name, child.visibility,
                       ROW_NUMBER() OVER (PARTITION BY item.user_list_id ORDER BY item.position ASC) AS position_rank
                FROM user_list_items item
                JOIN user_lists child ON child.id = item.child_list_id
                JOIN users owner ON owner.id = child.user_id
                WHERE item.user_list_id IN :parentIds
                AND NOT EXISTS (SELECT 1 FROM user_blocks b
                                WHERE (b.blocker_id = :viewerId AND b.blocked_id = child.user_id)
                                   OR (b.blocker_id = child.user_id AND b.blocked_id = :viewerId))
                AND (child.user_id = :viewerId OR child.visibility = 'PUBLIC'
                    OR (child.visibility = 'FOLLOWERS' AND EXISTS (
                        SELECT 1 FROM followers f WHERE f.follower_id = :viewerId
                        AND f.followed_id = child.user_id AND f.status = 'ACCEPTED')))
            ) ranked WHERE position_rank <= 5
            ORDER BY parent_id ASC, position_rank ASC
            """, nativeQuery = true)
    List<NestedPreview> findNestedPreviewsByParentIdIn(
            @Param("parentIds") Collection<UUID> parentIds, @Param("viewerId") UUID viewerId);

    interface NestedPreview {
        UUID getParentId();
        UUID getChildId();
        UUID getOwnerId();
        String getUsername();
        String getOwnerName();
        String getProfilePicture();
        Boolean getProfilePublic();
        String getChildName();
        String getVisibility();
    }

    long countByUserListId(UUID userListId);

    @Query("SELECT COALESCE(MAX(item.position), 0) FROM UserListItem item WHERE item.userList.id = :userListId")
    int findMaxPositionByUserListId(@Param("userListId") UUID userListId);

    @Query("""
            SELECT item FROM UserListItem item JOIN FETCH item.content
            WHERE item.userList.id = :userListId AND item.content.id IN :contentIds
            ORDER BY item.position ASC
            """)
    List<UserListItem> findByUserListIdAndContentIdInWithContent(
            @Param("userListId") UUID userListId, @Param("contentIds") Collection<UUID> contentIds);

    List<UserListItem> findByUserListIdOrderByPositionAsc(UUID userListId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT item FROM UserListItem item WHERE item.userList.id = :userListId ORDER BY item.position ASC, item.id ASC")
    List<UserListItem> findByUserListIdForUpdateOrderByPositionAsc(@Param("userListId") UUID userListId);

    @Query("""
            SELECT uli FROM UserListItem uli
            JOIN FETCH uli.userList
            JOIN FETCH uli.content
            WHERE uli.userList.user.id = :userId
            AND uli.content.id IN :contentIds
            ORDER BY uli.userList.id ASC, uli.position ASC
            """)
    List<UserListItem> findViewerMembershipsByContentIdIn(
            @Param("userId") UUID userId, @Param("contentIds") Collection<UUID> contentIds);

    List<UserListItem> findByChildListId(UUID childListId);

    @Query("SELECT DISTINCT item.userList.id FROM UserListItem item WHERE item.childList.id IN :childListIds")
    List<UUID> findDistinctParentListIdsByChildListIdIn(@Param("childListIds") Collection<UUID> childListIds);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT item FROM UserListItem item
            WHERE item.childList.id IN :childListIds
            ORDER BY item.userList.id ASC, item.position DESC, item.id ASC
            """)
    List<UserListItem> findByChildListIdInForUpdateOrderByParentAndPositionDesc(
            @Param("childListIds") Collection<UUID> childListIds);

    @Query("""
            SELECT uli FROM UserListItem uli
            LEFT JOIN FETCH uli.content
            LEFT JOIN FETCH uli.childList cl
            LEFT JOIN FETCH cl.user
            WHERE uli.userList.id = :userListId
            ORDER BY uli.position ASC
            """)
    List<UserListItem> findByUserListIdWithContentAndChildListOrderByPositionAsc(@Param("userListId") UUID userListId);

    boolean existsByUserListIdAndContentIdIsNotNull(UUID userListId);

    boolean existsByUserListIdAndChildListIdIsNotNull(UUID userListId);

    boolean existsByChildListId(UUID childListId);

    @Modifying
    @Query("""
            UPDATE UserListItem uli SET uli.position = uli.position + :offset
            WHERE uli.userList.id = :userListId
            AND uli.position >= :rangeStart AND uli.position <= :rangeEnd
            """)
    void parkPositionsInRange(
            @Param("userListId") UUID userListId,
            @Param("rangeStart") int rangeStart, @Param("rangeEnd") int rangeEnd, @Param("offset") int offset);

    @Modifying
    @Query("""
            UPDATE UserListItem uli SET uli.position = uli.position - :offset + :delta
            WHERE uli.userList.id = :userListId
            AND uli.position > :offset
            """)
    void settleParkedPositions(
            @Param("userListId") UUID userListId,
            @Param("offset") int offset, @Param("delta") int delta);

    @Query("""
            SELECT uli FROM UserListItem uli
            JOIN FETCH uli.content
            WHERE uli.userList.id IN :userListIds
            AND (
                SELECT COUNT(previousItem)
                FROM UserListItem previousItem
                WHERE previousItem.userList.id = uli.userList.id
                AND previousItem.content.id IS NOT NULL
                AND previousItem.position <= uli.position
            ) <= 5
            ORDER BY uli.userList.id ASC, uli.position ASC
            """)
    List<UserListItem> findContentItemsByUserListIdInOrderByPosition(@Param("userListIds") Collection<UUID> userListIds);

    @Query("""
            SELECT uli FROM UserListItem uli
            JOIN FETCH uli.content
            WHERE uli.userList.id IN :userListIds
            AND uli.content.id IS NOT NULL
            ORDER BY uli.userList.id ASC, uli.position ASC
            """)
    List<UserListItem> findAllContentItemsByUserListIdInOrderByPosition(
            @Param("userListIds") Collection<UUID> userListIds);

    @Query("""
            SELECT uli.userList.id AS userListId, COUNT(uli) AS count FROM UserListItem uli
            WHERE uli.userList.id IN :userListIds
            AND uli.childList.id IS NOT NULL
            GROUP BY uli.userList.id
            """)
    List<UserListCount> countNestedListsByUserListIdIn(@Param("userListIds") Collection<UUID> userListIds);

    @Query("""
            SELECT DISTINCT uli.content.type FROM UserListItem uli
            WHERE uli.userList.id = :userListId
            AND uli.content.id IS NOT NULL
            """)
    Set<ContentType> findDistinctContentTypesByUserListId(@Param("userListId") UUID userListId);

    @Query("""
            SELECT DISTINCT uli.userList.id AS userListId, uli.content.type AS type FROM UserListItem uli
            WHERE uli.userList.id IN :userListIds
            AND uli.content.id IS NOT NULL
            """)
    List<UserListContentType> findDistinctContentTypesByUserListIdIn(@Param("userListIds") Collection<UUID> userListIds);

    @Query("""
            SELECT uli.userList.id AS userListId, COUNT(uli) AS count FROM UserListItem uli
            WHERE uli.userList.id IN :userListIds
            AND uli.content.id IS NOT NULL
            GROUP BY uli.userList.id
            """)
    List<UserListCount> countContentItemsByUserListIdIn(@Param("userListIds") Collection<UUID> userListIds);

    @Query("""
            SELECT uli.userList.id AS userListId, COUNT(uli) AS count FROM UserListItem uli
            WHERE uli.userList.id IN :userListIds
            GROUP BY uli.userList.id
            """)
    List<UserListCount> countAllItemsByUserListIdIn(@Param("userListIds") Collection<UUID> userListIds);

    @Query("""
            SELECT uli.userList.id AS userListId, COALESCE(SUM(CASE WHEN uli.content.type = com.watchwise.watchwise_api.content.entity.ContentType.SERIES THEN uli.content.totalRuntimeMinutes ELSE uli.content.runtimeMinutes END), 0) AS total FROM UserListItem uli
            WHERE uli.userList.id IN :userListIds
            AND uli.content.id IS NOT NULL
            GROUP BY uli.userList.id
            """)
    List<UserListSum> sumRuntimeMinutesByUserListIdIn(@Param("userListIds") Collection<UUID> userListIds);

    @Query("""
            SELECT DISTINCT uli.userList.id FROM UserListItem uli
            WHERE uli.userList.id IN :userListIds
            AND uli.content.id = :contentId
            """)
    Set<UUID> findUserListIdsContainingContent(
            @Param("userListIds") Collection<UUID> userListIds, @Param("contentId") UUID contentId);

    @Query("""
            SELECT DISTINCT c.type AS type, c.tmdbId AS tmdbId, c.seriesTmdbId AS seriesTmdbId
            FROM UserListItem item
            JOIN item.content c
            WHERE c.type IN (com.watchwise.watchwise_api.content.entity.ContentType.MOVIE,
                             com.watchwise.watchwise_api.content.entity.ContentType.SERIES)
            AND (c.tmdbId IN :tmdbIds)
            AND (item.userList.user.id = :userId OR EXISTS (
                SELECT 1 FROM UserListItem parentItem
                WHERE parentItem.childList.id = item.userList.id
                AND parentItem.userList.user.id = :userId
            ))
            """)
    List<PersonCreditMedia> findViewerMediaForPersonCredits(
            @Param("userId") UUID userId, @Param("tmdbIds") Collection<String> tmdbIds);

    interface UserListCount {
        UUID getUserListId();
        long getCount();
    }

    interface UserListSum {
        UUID getUserListId();
        long getTotal();
    }

    interface UserListContentType {
        UUID getUserListId();
        ContentType getType();
    }

    interface PersonCreditMedia {
        ContentType getType();
        String getTmdbId();
        String getSeriesTmdbId();
    }

}
