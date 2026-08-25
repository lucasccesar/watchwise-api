package com.watchwise.watchwise_api.userlist.repository;

import com.watchwise.watchwise_api.userlist.entity.UserList;
import com.watchwise.watchwise_api.userlist.entity.UserListVisibility;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface UserListRepository extends JpaRepository<UserList, UUID> {

    List<UserList> findByUserId(UUID userId);

    Page<UserList> findByUserId(UUID userId, Pageable pageable);

    Page<UserList> findByUserIdAndVisibilityIn(UUID userId, Collection<UserListVisibility> visibilities, Pageable pageable);

    @Modifying
    @Query("UPDATE UserList u SET u.likesCount = u.likesCount + 1 WHERE u.id = :id")
    void incrementLikesCount(@Param("id") UUID id);

    @Modifying
    @Query("UPDATE UserList u SET u.likesCount = u.likesCount - 1 WHERE u.id = :id AND u.likesCount > 0")
    void decrementLikesCount(@Param("id") UUID id);

}