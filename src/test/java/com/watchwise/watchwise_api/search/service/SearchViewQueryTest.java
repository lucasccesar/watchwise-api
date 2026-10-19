package com.watchwise.watchwise_api.search.service;

import com.watchwise.watchwise_api.common.exception.BadRequestException;
import com.watchwise.watchwise_api.pickstemplate.entity.PickOrigin;
import com.watchwise.watchwise_api.userlist.dto.UserListViewItemType;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SearchViewQueryTest {

    private static final UUID VIEWER_ID = UUID.randomUUID();

    @Test
    void shouldRejectOriginOutsidePicksTemplateScope() {
        assertThatThrownBy(() -> new SearchViewQuery(
                VIEWER_ID, "night", SearchViewScope.USER, 1, 10, PickOrigin.OFFICIAL, null))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("origin is only supported for PICKS_TEMPLATE search view");
    }

    @Test
    void shouldRejectItemTypeOutsideListScope() {
        assertThatThrownBy(() -> new SearchViewQuery(
                VIEWER_ID, "night", SearchViewScope.USER, 1, 10, null, UserListViewItemType.MOVIE))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("itemType is only supported for LIST search view");
    }
}
