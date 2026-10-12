package com.watchwise.watchwise_api.userlist.service;

import com.watchwise.watchwise_api.userlist.dto.UserListDetailViewResponseDTO;
import com.watchwise.watchwise_api.userlist.dto.UserListItemPageRequest;

public interface UserListDetailViewService {

    UserListDetailViewResponseDTO getView(UserListItemPageRequest request);
}
