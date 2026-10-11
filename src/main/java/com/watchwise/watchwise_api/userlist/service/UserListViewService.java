package com.watchwise.watchwise_api.userlist.service;

import com.watchwise.watchwise_api.userlist.dto.UserListCardDTO;
import com.watchwise.watchwise_api.userlist.dto.UserListViewRequest;
import org.springframework.data.domain.Page;

public interface UserListViewService {
    Page<UserListCardDTO> getView(UserListViewRequest request);
}
