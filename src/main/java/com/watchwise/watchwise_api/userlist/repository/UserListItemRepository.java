package com.watchwise.watchwise_api.userlist.repository;

import com.watchwise.watchwise_api.userlist.entity.UserListItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface UserListItemRepository extends JpaRepository<UserListItem, UUID> {

    List<UserListItem> findByUserListIdOrderByPositionAsc(UUID userListId);

    boolean existsByUserListIdAndContentIdIsNotNull(UUID userListId);

    boolean existsByUserListIdAndChildListIdIsNotNull(UUID userListId);

}
