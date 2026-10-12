package com.watchwise.watchwise_api.userlist.dto;

import com.watchwise.watchwise_api.content.dto.ContentCardDTO;
import com.watchwise.watchwise_api.content.dto.WatchStatus;
import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.common.dto.PageResponseDTO;
import com.watchwise.watchwise_api.user.dto.UserPreviewDTO;
import com.watchwise.watchwise_api.userlist.entity.UserList;
import com.watchwise.watchwise_api.userlist.entity.UserListVisibility;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UserListItemPageRequestTest {

    @Test
    void shouldTrimGenreAndPreserveRawPageValues() {
        UserListItemPageRequest request = request("  Science Fiction  ", WatchStatus.WATCHED, -2, 0);

        assertThat(request.genre()).isEqualTo("Science Fiction");
        assertThat(request.page()).isEqualTo(-2);
        assertThat(request.size()).isZero();
    }

    @Test
    void shouldNormalizeAbsentAndBlankGenreToNull() {
        assertThat(request(null, null, null, null).genre()).isNull();
        assertThat(request(" \t ", null, null, null).genre()).isNull();
    }

    @Test
    void shouldAcceptEveryWatchStatusValue() {
        for (WatchStatus status : WatchStatus.values()) {
            assertThat(request(null, status, null, null).watchStatus()).isEqualTo(status);
        }
    }

    @Test
    void shouldDefensivelyCopyPageAndNestedPreviewCollections() {
        List<com.watchwise.watchwise_api.userlist.dto.UserListItemResponseDTO> itemSource = new ArrayList<>();
        List<ContentCardDTO> previewSource = new ArrayList<>();
        UserListItemPage page = new UserListItemPage(
                UserList.builder().build(), itemSource, null, 0, null, 0, 0,
                0L, 0, false, 0L, 0L, 0L, null, null);
        UserListNestedDetailDTO nested = new UserListNestedDetailDTO(
                new UserPreviewDTO(UUID.randomUUID(), "owner", "Owner", null, true), UUID.randomUUID(),
                "Nested", null, UserListVisibility.PUBLIC, LocalDateTime.now(), LocalDateTime.now(),
                UserListItemScope.MOVIE_OR_SERIES, 0L, 0, 0L, previewSource);

        itemSource.add(null);
        previewSource.add(null);

        assertThat(page.items()).isEmpty();
        assertThat(nested.previewCards()).isEmpty();
        assertThatThrownBy(() -> page.items().add(null)).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> nested.previewCards().add(null)).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void shouldRequireExactlyOneVisualItemTarget() {
        ContentCardDTO card = new ContentCardDTO(
                UUID.randomUUID(), ContentType.MOVIE, "42", null, null, null, "Film", null, null,
                null, null, null, null, null, null, List.of(), null, null, null);
        LocalDateTime now = LocalDateTime.now();

        assertThatIllegalArgumentException().isThrownBy(() -> visualItem(null, null, now));
        assertThatIllegalArgumentException().isThrownBy(() -> visualItem(card, nested(), now));
        assertThat(visualItem(card, null, now).card()).isSameAs(card);
        assertThat(visualItem(null, nested(), now).nestedList()).isNotNull();
    }

    @Test
    void shouldRejectNestedPreviewWithMoreThanFiveCards() {
        ContentCardDTO card = card();

        assertThatIllegalArgumentException().isThrownBy(() -> nested(List.of(card, card, card, card, card, card)));
    }

    @Test
    void shouldDefensivelyCopyVisualResponsePageContent() {
        List<UserListDetailItemDTO> source = new ArrayList<>();
        source.add(visualItem(card(), null, LocalDateTime.now()));
        UserListDetailViewResponseDTO response = new UserListDetailViewResponseDTO(
                UUID.randomUUID(), "List", null, UserListVisibility.PUBLIC, 0.0,
                LocalDateTime.now(), LocalDateTime.now(), 0, false, 1L, 0L, 0L, null,
                UserListItemScope.MOVIE_OR_SERIES, new PageResponseDTO<>(source, 1, 24, 1, 1, false));

        source.clear();

        assertThat(response.items().content()).hasSize(1);
        assertThatThrownBy(() -> response.items().content().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    private static UserListItemPageRequest request(String genre, WatchStatus status, Integer page, Integer size) {
        return new UserListItemPageRequest(
                UUID.randomUUID(), UUID.randomUUID(), ContentType.MOVIE, genre,
                "createdAt", "DESC", status, page, size);
    }

    private static UserListDetailItemDTO visualItem(
            ContentCardDTO card, UserListNestedDetailDTO nested, LocalDateTime now) {
        return new UserListDetailItemDTO(
                UUID.randomUUID(), card, nested, 1, null, now, now, null,
                null, null, null, null);
    }

    private static UserListNestedDetailDTO nested() {
        return nested(List.of());
    }

    private static UserListNestedDetailDTO nested(List<ContentCardDTO> previewCards) {
        return new UserListNestedDetailDTO(
                new UserPreviewDTO(UUID.randomUUID(), "owner", "Owner", null, true), UUID.randomUUID(),
                "Nested", null, UserListVisibility.PUBLIC, LocalDateTime.now(), LocalDateTime.now(),
                UserListItemScope.MOVIE_OR_SERIES, 0L, 0, 0L, previewCards);
    }

    private static ContentCardDTO card() {
        return new ContentCardDTO(
                UUID.randomUUID(), ContentType.MOVIE, "42", null, null, null, "Film", null, null,
                null, null, null, null, null, null, List.of(), null, null, null);
    }
}
