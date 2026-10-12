package com.watchwise.watchwise_api.userlist.service;

import com.watchwise.watchwise_api.userlist.dto.UserListItemPage;
import com.watchwise.watchwise_api.userlist.dto.UserListItemPageRequest;

public interface UserListItemPageReader {

    UserListItemPage read(UserListItemPageRequest request);
}
