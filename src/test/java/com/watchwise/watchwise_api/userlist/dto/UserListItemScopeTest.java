package com.watchwise.watchwise_api.userlist.dto;

import com.watchwise.watchwise_api.content.entity.ContentType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class UserListItemScopeTest {

    @Test
    @DisplayName("[forContentType] Should Map Movie And Series To MovieOrSeries - When Given Either Type")
    void shouldMapMovieAndSeriesToMovieOrSeriesWhenGivenEitherType() {
        assertThat(UserListItemScope.forContentType(ContentType.MOVIE)).isEqualTo(UserListItemScope.MOVIE_OR_SERIES);
        assertThat(UserListItemScope.forContentType(ContentType.SERIES)).isEqualTo(UserListItemScope.MOVIE_OR_SERIES);
    }

    @Test
    @DisplayName("[forContentType] Should Map Season To Season - When Given Season")
    void shouldMapSeasonToSeasonWhenGivenSeason() {
        assertThat(UserListItemScope.forContentType(ContentType.SEASON)).isEqualTo(UserListItemScope.SEASON);
    }

    @Test
    @DisplayName("[forContentType] Should Map Episode To Episode - When Given Episode")
    void shouldMapEpisodeToEpisodeWhenGivenEpisode() {
        assertThat(UserListItemScope.forContentType(ContentType.EPISODE)).isEqualTo(UserListItemScope.EPISODE);
    }

    @Test
    @DisplayName("[resolve] Should Return List - When HasNestedLists Is True Regardless Of Distinct Types")
    void shouldReturnListWhenHasNestedListsIsTrueRegardlessOfDistinctTypes() {
        assertThat(UserListItemScope.resolve(Set.of(ContentType.MOVIE), true)).isEqualTo(UserListItemScope.LIST);
        assertThat(UserListItemScope.resolve(Set.of(), true)).isEqualTo(UserListItemScope.LIST);
    }

    @Test
    @DisplayName("[resolve] Should Return Null - When Distinct Types Is Empty And HasNestedLists Is False")
    void shouldReturnNullWhenDistinctTypesIsEmptyAndHasNestedListsIsFalse() {
        assertThat(UserListItemScope.resolve(Set.of(), false)).isNull();
    }

    @Test
    @DisplayName("[resolve] Should Return The Single Group - When All Distinct Types Share One Group")
    void shouldReturnTheSingleGroupWhenAllDistinctTypesShareOneGroup() {
        assertThat(UserListItemScope.resolve(Set.of(ContentType.MOVIE, ContentType.SERIES), false))
                .isEqualTo(UserListItemScope.MOVIE_OR_SERIES);
    }

    @Test
    @DisplayName("[resolve] Should Return Mixed - When Distinct Types Span More Than One Group")
    void shouldReturnMixedWhenDistinctTypesSpanMoreThanOneGroup() {
        assertThat(UserListItemScope.resolve(Set.of(ContentType.MOVIE, ContentType.EPISODE), false))
                .isEqualTo(UserListItemScope.MIXED);
    }
}
