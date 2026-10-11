package com.watchwise.watchwise_api.userlist.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.watchwise.watchwise_api.content.dto.ContentCardDTO;
import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.user.dto.UserPreviewDTO;
import com.watchwise.watchwise_api.userlist.dto.UserListCardDTO;
import com.watchwise.watchwise_api.userlist.dto.UserListItemScope;
import com.watchwise.watchwise_api.userlist.dto.UserListPreviewDTO;
import com.watchwise.watchwise_api.userlist.dto.UserListViewItemType;
import com.watchwise.watchwise_api.userlist.dto.UserListViewQueryMode;
import com.watchwise.watchwise_api.userlist.dto.UserListViewSort;
import com.watchwise.watchwise_api.userlist.dto.UserListViewSource;
import com.watchwise.watchwise_api.common.exception.BadRequestException;
import com.watchwise.watchwise_api.userlist.entity.UserList;
import com.watchwise.watchwise_api.userlist.entity.UserListVisibility;
import com.watchwise.watchwise_api.user.entity.User;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class UserListViewAssemblerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void visualEnumsMapApiSortValuesAndRejectUnknownValues() {
        assertArrayEquals(new UserListViewSource[]{UserListViewSource.OWNED, UserListViewSource.LIKED, UserListViewSource.ALL}, UserListViewSource.values());
        assertArrayEquals(new UserListViewItemType[]{UserListViewItemType.MOVIE, UserListViewItemType.SERIES, UserListViewItemType.SEASON, UserListViewItemType.EPISODE, UserListViewItemType.LIST}, UserListViewItemType.values());
        assertArrayEquals(new UserListViewQueryMode[]{UserListViewQueryMode.MY, UserListViewQueryMode.USER, UserListViewQueryMode.DISCOVER}, UserListViewQueryMode.values());
        assertEquals(UserListViewSort.NAME, UserListViewSort.fromApiValue("name"));
        assertEquals(UserListViewSort.LIKES_COUNT, UserListViewSort.fromApiValue("likesCount"));
        assertEquals(UserListViewSort.ITEMS_COUNT, UserListViewSort.fromApiValue("itemsCount"));
        assertEquals(UserListViewSort.COMMENTS_COUNT, UserListViewSort.fromApiValue("commentsCount"));
        assertEquals(UserListViewSort.UPDATED_AT, UserListViewSort.fromApiValue("updatedAt"));
        assertEquals(UserListViewSort.RANK, UserListViewSort.fromApiValue("rank"));
        assertThrows(BadRequestException.class, () -> UserListViewSort.fromApiValue(" "));
        assertThrows(BadRequestException.class, () -> UserListViewSort.fromApiValue("unknown"));
    }

    @Test
    void cardHasExactVisualJsonComponentsAndImmutableNullSafeCollections() throws Exception {
        UserListCardDTO card = new UserListCardDTO(UUID.randomUUID(), null, "n", null, null, null, null,
                null, null, 0, 0, false, 0, 0, null, null, false);
        assertEquals(List.of(), card.previewCards());
        assertEquals(List.of(), card.nestedListPreviews());
        assertThrows(UnsupportedOperationException.class, () -> card.previewCards().add(null));
        assertThrows(UnsupportedOperationException.class, () -> card.nestedListPreviews().add(null));
        var names = objectMapper.readTree(objectMapper.writeValueAsString(card)).fieldNames();
        List<String> actual = new ArrayList<>();
        names.forEachRemaining(actual::add);
        assertEquals(List.of("id", "user", "name", "description", "visibility", "createdAt", "updatedAt",
                "previewCards", "nestedListPreviews", "nestedListsCount", "likesCount", "likedByMe",
                "itemsCount", "commentsCount", "rank", "itemScope", "ownedByMe"), actual);
    }

    @Test
    void assemblesListsInInputOrderWithCappedSuppliedPreviewsAndBatchValues() {
        UUID viewerId = UUID.randomUUID();
        User ownerA = user(UUID.randomUUID(), "owner-a");
        User ownerB = user(viewerId, "owner-b");
        UserList first = list(UUID.randomUUID(), ownerA, "first");
        UserList second = list(UUID.randomUUID(), ownerB, "second");
        UserPreviewDTO ownerPreviewA = preview(ownerA);
        UserPreviewDTO ownerPreviewB = preview(ownerB);
        List<ContentCardDTO> sourceCards = new ArrayList<>();
        for (int i = 0; i < 7; i++) sourceCards.add(contentCard(i));
        List<UserListPreviewDTO> sourceNested = new ArrayList<>();
        for (int i = 0; i < 7; i++) sourceNested.add(new UserListPreviewDTO(UUID.randomUUID(), ownerPreviewA, "nested-" + i, UserListVisibility.PUBLIC));

        List<UserListCardDTO> cards = UserListViewAssembler.assemble(List.of(first, second),
                Map.of(ownerA.getId(), ownerPreviewA, ownerB.getId(), ownerPreviewB),
                Map.of(first.getId(), sourceCards), Map.of(first.getId(), sourceNested),
                Map.of(first.getId(), 12L), Map.of(first.getId(), 8L), Map.of(first.getId(), 3L),
                Map.of(first.getId(), 4L), Set.of(first.getId()), Map.of(first.getId(), UserListItemScope.MIXED), viewerId);

        assertEquals(List.of(first.getId(), second.getId()), cards.stream().map(UserListCardDTO::id).toList());
        assertSame(ownerPreviewA, cards.get(0).user());
        assertSame(ownerPreviewB, cards.get(1).user());
        assertFalse(cards.get(0).ownedByMe());
        assertTrue(cards.get(1).ownedByMe());
        assertTrue(cards.get(0).likedByMe());
        assertFalse(cards.get(1).likedByMe());
        assertEquals(sourceCards.subList(0, 5), cards.get(0).previewCards());
        assertSame(sourceCards.get(0), cards.get(0).previewCards().get(0));
        assertEquals(sourceNested.subList(0, 5), cards.get(0).nestedListPreviews());
        assertEquals(12, cards.get(0).nestedListsCount());
        assertEquals(8, cards.get(0).likesCount());
        assertEquals(3, cards.get(0).itemsCount());
        assertEquals(4, cards.get(0).commentsCount());
        assertEquals(UserListItemScope.MIXED, cards.get(0).itemScope());
        assertEquals(0, cards.get(1).likesCount());
        assertNull(cards.get(1).itemScope());
    }

    @Test
    void preservesRepeatedContentCardReferencesInEachListsOriginalOrder() {
        User owner = user(UUID.randomUUID(), "owner");
        UserList first = list(UUID.randomUUID(), owner, "first");
        UserList second = list(UUID.randomUUID(), owner, "second");
        ContentCardDTO repeatedCoordinate = contentCard(21);
        ContentCardDTO firstOnly = contentCard(22);
        ContentCardDTO secondOnly = contentCard(23);

        List<UserListCardDTO> cards = UserListViewAssembler.assemble(List.of(first, second),
                Map.of(owner.getId(), preview(owner)),
                Map.of(first.getId(), List.of(repeatedCoordinate, firstOnly),
                        second.getId(), List.of(secondOnly, repeatedCoordinate)),
                Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Set.of(), Map.of(), UUID.randomUUID());

        assertEquals(2, cards.size());
        assertEquals(List.of(repeatedCoordinate, firstOnly), cards.get(0).previewCards());
        assertEquals(List.of(secondOnly, repeatedCoordinate), cards.get(1).previewCards());
        assertSame(repeatedCoordinate, cards.get(0).previewCards().get(0));
        assertSame(repeatedCoordinate, cards.get(1).previewCards().get(1));
        assertNotSame(cards.get(0).previewCards(), cards.get(1).previewCards());
    }

    @Test
    void overviewDoesNotAddProgressOrRuntimeToCallerSuppliedCards() {
        User owner = user(UUID.randomUUID(), "owner");
        UserList list = list(UUID.randomUUID(), owner, "list");
        ContentCardDTO supplied = contentCard(9);
        var card = UserListViewAssembler.assemble(List.of(list), Map.of(owner.getId(), preview(owner)),
                Map.of(list.getId(), List.of(supplied)), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(),
                Set.of(), Map.of(), UUID.randomUUID()).get(0);
        assertSame(supplied, card.previewCards().get(0));
        assertNull(card.previewCards().get(0).totalRuntimeMinutes());
        assertNull(card.previewCards().get(0).stats());
        assertNull(card.previewCards().get(0).viewerState());
    }

    private static User user(UUID id, String username) {
        return User.builder().id(id).username(username).name(username).profilePicture(null).isProfilePublic(true).build();
    }

    private static UserPreviewDTO preview(User user) {
        return new UserPreviewDTO(user.getId(), user.getUsername(), user.getName(), user.getProfilePicture(), user.getIsProfilePublic());
    }

    private static UserList list(UUID id, User owner, String name) {
        return UserList.builder().id(id).user(owner).name(name).description("description").visibility(UserListVisibility.PUBLIC)
                .createdAt(LocalDateTime.parse("2026-10-11T10:00:00")).updatedAt(LocalDateTime.parse("2026-10-11T11:00:00")).rank(1).build();
    }

    private static ContentCardDTO contentCard(int index) {
        return new ContentCardDTO(UUID.randomUUID(), ContentType.MOVIE, String.valueOf(index), null, null, null,
                "title-" + index, null, null, LocalDate.of(2020, 1, 1), 2020, 100, null, null, null,
                List.of(), null, null, null);
    }
}
