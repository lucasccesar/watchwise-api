package com.watchwise.watchwise_api.userlist.repository;

import com.watchwise.watchwise_api.userlist.entity.UserListItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface UserListItemRepository extends JpaRepository<UserListItem, UUID> {

    long countByUserListId(UUID userListId);

    List<UserListItem> findByUserListIdOrderByPositionAsc(UUID userListId);

    List<UserListItem> findByChildListId(UUID childListId);

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
            ORDER BY uli.userList.id ASC, uli.position ASC
            """)
    List<UserListItem> findContentItemsByUserListIdInOrderByPosition(@Param("userListIds") Collection<UUID> userListIds);

    @Query("""
            SELECT uli.userList.id AS userListId, COUNT(uli) AS count FROM UserListItem uli
            WHERE uli.userList.id IN :userListIds
            AND uli.childList.id IS NOT NULL
            GROUP BY uli.userList.id
            """)
    List<UserListCount> countNestedListsByUserListIdIn(@Param("userListIds") Collection<UUID> userListIds);

    @Query("""
            SELECT uli.userList.id AS userListId, COUNT(uli) AS count FROM UserListItem uli
            WHERE uli.userList.id IN :userListIds
            AND uli.content.id IS NOT NULL
            GROUP BY uli.userList.id
            """)
    List<UserListCount> countContentItemsByUserListIdIn(@Param("userListIds") Collection<UUID> userListIds);

    @Query("""
            SELECT uli.userList.id AS userListId, COUNT(DISTINCT uli.content.id) AS count FROM UserListItem uli
            WHERE uli.userList.id IN :userListIds
            AND uli.content.id IS NOT NULL
            AND EXISTS (
                SELECT 1 FROM DiaryEntry de
                WHERE de.user.id = :ownerId
                AND de.content.id = uli.content.id
            )
            GROUP BY uli.userList.id
            """)
    List<UserListCount> countWatchedContentItemsByUserListIdIn(
            @Param("userListIds") Collection<UUID> userListIds, @Param("ownerId") UUID ownerId);

    interface UserListCount {
        UUID getUserListId();
        long getCount();
    }

}
