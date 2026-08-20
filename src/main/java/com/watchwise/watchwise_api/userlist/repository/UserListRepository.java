package com.watchwise.watchwise_api.userlist.repository;

import com.watchwise.watchwise_api.userlist.entity.UserList;
import com.watchwise.watchwise_api.userlist.entity.UserListVisibility;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface UserListRepository extends JpaRepository<UserList, UUID> {

    List<UserList> findByUserId(UUID userId);

    Page<UserList> findByUserId(UUID userId, Pageable pageable);

    Page<UserList> findByUserIdAndVisibilityIn(UUID userId, Collection<UserListVisibility> visibilities, Pageable pageable);

}