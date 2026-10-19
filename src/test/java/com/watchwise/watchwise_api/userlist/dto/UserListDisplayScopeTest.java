package com.watchwise.watchwise_api.userlist.dto;

import com.watchwise.watchwise_api.content.entity.ContentType;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class UserListDisplayScopeTest {

    @Test
    void shouldResolveExactVisualScopes() {
        assertThat(UserListDisplayScope.resolve(Set.of(ContentType.MOVIE), false))
                .isEqualTo(UserListDisplayScope.MOVIES);
        assertThat(UserListDisplayScope.resolve(Set.of(ContentType.SERIES), false))
                .isEqualTo(UserListDisplayScope.SERIES);
        assertThat(UserListDisplayScope.resolve(Set.of(ContentType.MOVIE, ContentType.SERIES), false))
                .isEqualTo(UserListDisplayScope.MOVIES_AND_SERIES);
        assertThat(UserListDisplayScope.resolve(Set.of(ContentType.MOVIE, ContentType.SEASON), false))
                .isEqualTo(UserListDisplayScope.MIXED);
        assertThat(UserListDisplayScope.resolve(Set.of(), true))
                .isEqualTo(UserListDisplayScope.LISTS);
    }
}
