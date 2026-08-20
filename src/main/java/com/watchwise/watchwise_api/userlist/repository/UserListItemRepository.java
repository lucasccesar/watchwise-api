package com.watchwise.watchwise_api.userlist.repository;

import com.watchwise.watchwise_api.userlist.entity.UserListItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface UserListItemRepository extends JpaRepository<UserListItem, UUID> {

    List<UserListItem> findByUserListIdOrderByPositionAsc(UUID userListId);

    List<UserListItem> findTop5ByUserListIdAndContentIdIsNotNullOrderByPositionAsc(UUID userListId);

    boolean existsByUserListIdAndContentIdIsNotNull(UUID userListId);

    boolean existsByUserListIdAndChildListIdIsNotNull(UUID userListId);

    long countByUserListIdAndChildListIdIsNotNull(UUID userListId);

    long countByUserListIdAndContentIdIsNotNull(UUID userListId);

    @Query("""
            SELECT COUNT(DISTINCT uli.content.id) FROM UserListItem uli
            WHERE uli.userList.id = :userListId
            AND uli.content.id IS NOT NULL
            AND EXISTS (
                SELECT 1 FROM DiaryEntry de
                WHERE de.user.id = :ownerId
                AND de.content.id = uli.content.id
            )
            """)
    long countWatchedContentItems(@Param("userListId") UUID userListId, @Param("ownerId") UUID ownerId);

}
