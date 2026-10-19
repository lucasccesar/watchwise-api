package com.watchwise.watchwise_api.search.service;

import com.watchwise.watchwise_api.common.exception.BadRequestException;
import com.watchwise.watchwise_api.pickstemplate.entity.PickOrigin;
import com.watchwise.watchwise_api.userlist.dto.UserListViewItemType;

import java.util.UUID;

public record SearchViewQuery(
        UUID viewerId,
        String query,
        SearchViewScope scope,
        int page,
        int size,
        PickOrigin origin,
        UserListViewItemType itemType) {

    public SearchViewQuery {
        if (origin != null && scope != SearchViewScope.PICKS_TEMPLATE) {
            throw new BadRequestException("origin is only supported for PICKS_TEMPLATE search view");
        }
        if (itemType != null && scope != SearchViewScope.LIST) {
            throw new BadRequestException("itemType is only supported for LIST search view");
        }
    }
}
