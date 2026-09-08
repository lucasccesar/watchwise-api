package com.watchwise.watchwise_api.search.dto;

import com.watchwise.watchwise_api.content.entity.MovieOrSeriesType;
import com.watchwise.watchwise_api.user.dto.UserPreviewDTO;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class SearchResultDTOTest {

    @Test
    void shouldKeepSearchCardsGroupedByTheirSource() {
        UserPreviewDTO owner = new UserPreviewDTO(UUID.randomUUID(), "lucas", "https://example.com/lucas.png", true);
        SearchContentDTO content = new SearchContentDTO("550", MovieOrSeriesType.MOVIE, "Fight Club", "/poster.jpg", 1999);
        SearchPersonDTO person = new SearchPersonDTO("287", "Brad Pitt", "/photo.jpg");
        SearchUserListDTO list = new SearchUserListDTO(UUID.randomUUID(), owner, "Favorites", List.of(), 0L);

        SearchResultDTO result = new SearchResultDTO(List.of(content), List.of(person), List.of(list), List.of(owner));

        assertThat(result.contents()).containsExactly(content);
        assertThat(result.people()).containsExactly(person);
        assertThat(result.lists()).containsExactly(list);
        assertThat(result.users()).containsExactly(owner);
        assertThat(result.lists().getFirst().user()).isEqualTo(owner);
        assertThat(result.lists().getFirst().previewItems()).isEmpty();
    }
}
