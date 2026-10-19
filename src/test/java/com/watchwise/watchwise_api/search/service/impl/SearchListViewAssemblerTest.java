package com.watchwise.watchwise_api.search.service.impl;

import com.watchwise.watchwise_api.content.dto.ContentCardDTO;
import com.watchwise.watchwise_api.content.dto.ContentPreviewStatus;
import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.search.dto.SearchListViewDTO;
import com.watchwise.watchwise_api.user.dto.UserPreviewDTO;
import com.watchwise.watchwise_api.userlist.dto.UserListCardDTO;
import com.watchwise.watchwise_api.userlist.dto.UserListDisplayScope;
import com.watchwise.watchwise_api.userlist.entity.UserListVisibility;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class SearchListViewAssemblerTest {

    @Test
    void shouldMapAlreadyEnrichedCardsAndPreservePreviewOrder() {
        UUID listId = UUID.randomUUID();
        UserListCardDTO source = new UserListCardDTO(
                listId,
                new UserPreviewDTO(UUID.randomUUID(), "lucas", "Lucas", "/lucas.png", true),
                "Night lists", "After dark", UserListVisibility.PUBLIC,
                LocalDateTime.now().minusDays(2), LocalDateTime.now(),
                List.of(contentCard("1", "Night Film", "/night.jpg")), List.of(),
                0, 4, true, 12, 3, null, null,
                UserListDisplayScope.MOVIES, true);

        SearchListViewDTO result = new SearchListViewAssembler().assemble(List.of(source)).getFirst();

        assertThat(result.id()).isEqualTo(listId);
        assertThat(result.owner().name()).isEqualTo("Lucas");
        assertThat(result.displayScope()).isEqualTo(UserListDisplayScope.MOVIES);
        assertThat(result.previewCards()).singleElement().satisfies(card -> {
            assertThat(card.title()).isEqualTo("Night Film");
            assertThat(card.posterUrl()).isEqualTo("https://image.tmdb.org/t/p/w500/night.jpg");
            assertThat(card.previewStatus()).isEqualTo(ContentPreviewStatus.AVAILABLE);
        });
    }

    private ContentCardDTO contentCard(String tmdbId, String title, String posterPath) {
        return new ContentCardDTO(UUID.randomUUID(), ContentType.MOVIE, tmdbId, null, null, null,
                title, posterPath, null, LocalDate.of(2020, 1, 1), 2020, 100, null,
                null, null, List.of(), null, null, ContentPreviewStatus.AVAILABLE);
    }
}
