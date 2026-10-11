package com.watchwise.watchwise_api.userlist.repository;

import com.watchwise.watchwise_api.userlist.dto.UserListViewQuery;
import com.watchwise.watchwise_api.userlist.entity.UserList;
import org.springframework.data.domain.Page;

public interface UserListViewRepository {
    Page<UserList> findPage(UserListViewQuery query);
}
