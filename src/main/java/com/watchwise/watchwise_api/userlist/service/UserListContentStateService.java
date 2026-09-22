package com.watchwise.watchwise_api.userlist.service;

import com.watchwise.watchwise_api.userlist.entity.UserListItem;

import java.util.Collection;
import java.util.UUID;

public interface UserListContentStateService {

    UserListContentStateResult resolve(UUID viewerId, Collection<UserListItem> items);
}
