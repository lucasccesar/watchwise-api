package com.watchwise.watchwise_api.userlist.service.impl;

import com.watchwise.watchwise_api.common.exception.ForbiddenException;
import com.watchwise.watchwise_api.common.exception.NotFoundException;
import com.watchwise.watchwise_api.content.entity.Content;
import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.content.service.impl.ContentCardAssembler;
import com.watchwise.watchwise_api.contentposter.service.UserContentPosterService;
import com.watchwise.watchwise_api.follower.entity.FollowStatus;
import com.watchwise.watchwise_api.follower.repository.FollowerRepository;
import com.watchwise.watchwise_api.user.entity.User;
import com.watchwise.watchwise_api.userblock.service.UserBlockAccessService;
import com.watchwise.watchwise_api.userlist.dto.UserListEditorDTO;
import com.watchwise.watchwise_api.userlist.entity.UserList;
import com.watchwise.watchwise_api.userlist.entity.UserListItem;
import com.watchwise.watchwise_api.userlist.entity.UserListVisibility;
import com.watchwise.watchwise_api.userlist.repository.UserListItemRepository;
import com.watchwise.watchwise_api.userlist.repository.UserListRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UserListEditorReaderTest {
    private final UUID ownerId = UUID.randomUUID();
    private final UUID stranger = UUID.randomUUID();
    private final UUID listId = UUID.randomUUID();

    @Mock UserListRepository lists;
    @Mock UserListItemRepository items;
    @Mock FollowerRepository followers;
    @Mock UserBlockAccessService blocks;
    @Mock UserContentPosterService posters;
    @Mock ContentCardAssembler cards;
    @InjectMocks UserListEditorReader reader;

    @ParameterizedTest
    @ValueSource(ints = {0, 12, 30, 31, 48, 61, 100})
    void shouldReturnOrderedCompleteItemsWhenWithinEditorLimit(int count) {
        var list = stubList(UserListVisibility.PRIVATE);
        var ordered = ordered(list, count);
        when(items.findEditorItems(listId, PageRequest.of(0, 101))).thenReturn(ordered);
        if (count > 0) {
            when(posters.findByUserAndContentIds(eq(ownerId), anyCollection()))
                    .thenReturn(Map.of(ordered.getFirst().getContent().getId(), "/custom.png"));
        }

        var result = reader.read(ownerId, listId);

        assertThat(result.status()).isEqualTo(UserListEditorDTO.Status.READY);
        assertThat(result.name()).isEqualTo("Queue");
        assertThat(result.items()).extracting(UserListEditorDTO.Item::id)
                .containsExactlyElementsOf(ordered.stream().map(UserListItem::getId).toList());
        assertThat(result.items()).extracting(UserListEditorDTO.Item::position)
                .containsExactlyElementsOf(IntStream.rangeClosed(1, count).boxed().toList());
        verify(items).findEditorItems(listId, PageRequest.of(0, 101));
        verifyNoMoreInteractions(items);
        verifyNoInteractions(followers);
        if (count > 0) {
            verify(cards).assembleBasicMetadataWithKnownIds(anyCollection(), any(), argThat(map -> map.size() == count));
            verify(posters).findByUserAndContentIds(eq(ownerId), argThat(ids -> ids.size() == count));
            assertThat(result.items().getFirst().customPosterUrl()).isEqualTo("/custom.png");
            assertThat(result.items().getFirst().card().tmdbId()).isEqualTo("1");
            assertThat(result.items().getFirst().card().type()).isEqualTo(ContentType.MOVIE);
            assertThat(result.items().get(1).card().type()).isEqualTo(ContentType.SERIES);
        } else {
            verifyNoInteractions(cards, posters);
        }
    }

    @Test
    void shouldSignalLegacyReadWithoutPartialItemsWhenOverLimit() {
        var list = stubList(UserListVisibility.PUBLIC);
        when(items.findEditorItems(listId, PageRequest.of(0, 101))).thenReturn(ordered(list, 101));

        var result = reader.read(ownerId, listId);

        assertThat(result.status()).isEqualTo(UserListEditorDTO.Status.LEGACY);
        assertThat(result.items()).isNull();
        verifyNoInteractions(cards, posters);
    }

    @Test
    void shouldPreserveExistingPreviewAndVisibilityPathWhenNested() {
        var list = stubList(UserListVisibility.PRIVATE);
        when(items.findEditorItems(listId, PageRequest.of(0, 101)))
                .thenReturn(List.of(UserListItem.builder().userList(list).position(1).childList(list).build()));

        assertThat(reader.read(ownerId, listId).status()).isEqualTo(UserListEditorDTO.Status.LEGACY);
        verifyNoInteractions(cards, posters);
    }

    @Test
    void shouldReturnOnlyRedirectSignalWhenNonOwnerCanRead() {
        stubList(UserListVisibility.PUBLIC);
        var result = reader.read(stranger, listId);
        assertThat(result.status()).isEqualTo(UserListEditorDTO.Status.READ_ONLY);
        assertThat(result.items()).isNull();
        assertThat(result.name()).isNull();
        verify(blocks).assertContentVisible(stranger, ownerId);
        stubList(UserListVisibility.FOLLOWERS);
        when(followers.existsByFollowerIdAndFollowedIdAndStatus(stranger, ownerId, FollowStatus.ACCEPTED))
                .thenReturn(true);
        assertThat(reader.read(stranger, listId).status()).isEqualTo(UserListEditorDTO.Status.READ_ONLY);
        verifyNoInteractions(items, posters, cards);
    }

    @Test
    void shouldDenyPrivateAndBlockedThirdPartiesBeforeReadingItems() {
        stubList(UserListVisibility.PRIVATE);
        assertThatThrownBy(() -> reader.read(stranger, listId)).isInstanceOf(ForbiddenException.class);
        doThrow(new NotFoundException("Resource not found")).when(blocks).assertContentVisible(stranger, ownerId);
        assertThatThrownBy(() -> reader.read(stranger, listId)).isInstanceOf(NotFoundException.class);
        verifyNoInteractions(items, posters, cards);
    }

    private UserList stubList(UserListVisibility visibility) {
        var owner = User.builder().id(ownerId).build();
        var list = UserList.builder().id(listId).user(owner).name("Queue").visibility(visibility).build();
        when(lists.findByIdWithUser(listId)).thenReturn(Optional.of(list));
        return list;
    }

    private List<UserListItem> ordered(UserList list, int count) {
        return IntStream.rangeClosed(1, count).mapToObj(i -> UserListItem.builder().id(UUID.randomUUID())
                .position(i).userList(list).content(Content.builder().id(UUID.randomUUID())
                        .tmdbId(Integer.toString(i)).type(i % 2 == 0 ? ContentType.SERIES : ContentType.MOVIE).build())
                .build()).toList();
    }
}

