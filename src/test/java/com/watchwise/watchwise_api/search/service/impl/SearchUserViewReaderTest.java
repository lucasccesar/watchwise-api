package com.watchwise.watchwise_api.search.service.impl;

import com.watchwise.watchwise_api.diaryentry.repository.DiaryEntryRepository;
import com.watchwise.watchwise_api.follower.entity.FollowStatus;
import com.watchwise.watchwise_api.follower.repository.FollowerRepository;
import com.watchwise.watchwise_api.user.entity.User;
import com.watchwise.watchwise_api.user.repository.UserRepository;
import com.watchwise.watchwise_api.userlist.repository.UserListRepository;
import com.watchwise.watchwise_api.userblock.repository.UserBlockRepository;
import com.watchwise.watchwise_api.search.dto.SearchUserViewDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SearchUserViewReaderTest {

    private static final UUID VIEWER_ID = UUID.randomUUID();

    @Mock
    private UserRepository users;
    @Mock
    private FollowerRepository followers;
    @Mock
    private UserListRepository lists;
    @Mock
    private DiaryEntryRepository diary;
    @Mock
    private UserBlockRepository blocks;

    private SearchUserViewReader reader;

    @BeforeEach
    void setUp() {
        reader = new SearchUserViewReader(users, followers, lists, diary, blocks);
    }

    @Test
    void shouldHideBioForBlockedPublicProfilePreview() {
        User target = user(UUID.randomUUID(), "blocked-night", "Blocked Night", true);
        PageRequest pageable = PageRequest.of(0, 10);
        List<UUID> targetIds = List.of(target.getId());
        when(users.searchByNameOrUsernameForView(eq("night"), eq("night"), eq(pageable)))
                .thenReturn(new PageImpl<>(List.of(target), pageable, 1));
        when(blocks.findBlockedCounterpartIdsEitherDirection(VIEWER_ID, targetIds)).thenReturn(Set.of(target.getId()));

        SearchUserViewDTO card = reader.read(VIEWER_ID, "night", pageable).getContent().getFirst();

        assertThat(card.profileDetailsVisible()).isTrue();
        assertThat(card.description()).isNull();
    }

    @Test
    void shouldBatchCountsAndRedactPrivateDetails() {
        User publicUser = user(UUID.randomUUID(), "public-night", "Public Night", true);
        User privateUser = user(UUID.randomUUID(), "private-night", "Private Night", false);
        PageRequest pageable = PageRequest.of(0, 10);
        when(users.searchByNameOrUsernameForView(eq("night"), eq("night"), eq(pageable)))
                .thenReturn(new PageImpl<>(List.of(publicUser, privateUser), pageable, 2));

        FollowerRepository.ViewerRelationRow outgoing = mock(FollowerRepository.ViewerRelationRow.class);
        when(outgoing.getFollowerId()).thenReturn(VIEWER_ID);
        when(outgoing.getFollowedId()).thenReturn(privateUser.getId());
        when(outgoing.getStatus()).thenReturn(FollowStatus.PENDING);
        FollowerRepository.ViewerRelationRow incoming = mock(FollowerRepository.ViewerRelationRow.class);
        when(incoming.getFollowerId()).thenReturn(publicUser.getId());
        when(incoming.getFollowedId()).thenReturn(VIEWER_ID);
        when(incoming.getStatus()).thenReturn(FollowStatus.ACCEPTED);
        when(followers.findViewerRelations(eq(VIEWER_ID), eq(List.of(publicUser.getId(), privateUser.getId()))))
                .thenReturn(List.of(outgoing, incoming));

        FollowerRepository.UserCount followerCount = count(publicUser.getId(), 12L);
        UserListRepository.OwnerListCount listCount = listCount(publicUser.getId(), 7L);
        DiaryEntryRepository.UserDiaryCount diaryCount = diaryCount(publicUser.getId(), 20L);
        when(followers.countAcceptedFollowersByFollowedIdIn(anyCollection(), eq(FollowStatus.ACCEPTED)))
                .thenReturn(List.of(followerCount));
        when(lists.countListsVisibleToViewerByOwnerIdIn(eq(VIEWER_ID), anyCollection()))
                .thenReturn(List.of(listCount));
        when(diary.countVisibleDiaryEntriesByUserIdIn(anyCollection()))
                .thenReturn(List.of(diaryCount));

        var result = reader.read(VIEWER_ID, "night", pageable);

        assertThat(result.getTotalElements()).isEqualTo(2);
        SearchUserViewDTO publicCard = result.getContent().getFirst();
        assertThat(publicCard.profileDetailsVisible()).isTrue();
        assertThat(publicCard.watchedCount()).isEqualTo(20L);
        assertThat(publicCard.listsCount()).isEqualTo(7L);
        assertThat(publicCard.followersCount()).isEqualTo(12L);
        assertThat(publicCard.followsViewer()).isTrue();

        SearchUserViewDTO privateCard = result.getContent().get(1);
        assertThat(privateCard.profileDetailsVisible()).isFalse();
        assertThat(privateCard.description()).isEqualTo("description");
        assertThat(privateCard.watchedCount()).isNull();
        assertThat(privateCard.listsCount()).isNull();
        assertThat(privateCard.followersCount()).isNull();
        assertThat(privateCard.viewerFollowStatus()).isEqualTo(FollowStatus.PENDING);
    }

    private User user(UUID id, String username, String name, boolean publicProfile) {
        return User.builder().id(id).username(username).name(name).description("description")
                .profilePicture("/profile.png").isProfilePublic(publicProfile).build();
    }

    private FollowerRepository.UserCount count(UUID id, long value) {
        FollowerRepository.UserCount count = mock(FollowerRepository.UserCount.class);
        when(count.getUserId()).thenReturn(id);
        when(count.getCount()).thenReturn(value);
        return count;
    }

    private UserListRepository.OwnerListCount listCount(UUID id, long value) {
        UserListRepository.OwnerListCount count = mock(UserListRepository.OwnerListCount.class);
        when(count.getUserId()).thenReturn(id);
        when(count.getCount()).thenReturn(value);
        return count;
    }

    private DiaryEntryRepository.UserDiaryCount diaryCount(UUID id, long value) {
        DiaryEntryRepository.UserDiaryCount count = mock(DiaryEntryRepository.UserDiaryCount.class);
        when(count.getUserId()).thenReturn(id);
        when(count.getCount()).thenReturn(value);
        return count;
    }
}
