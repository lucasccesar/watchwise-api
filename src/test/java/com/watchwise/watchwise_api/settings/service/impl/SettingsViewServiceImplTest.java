package com.watchwise.watchwise_api.settings.service.impl;

import com.watchwise.watchwise_api.common.exception.NotFoundException;
import com.watchwise.watchwise_api.follower.entity.FollowStatus;
import com.watchwise.watchwise_api.follower.entity.Follower;
import com.watchwise.watchwise_api.follower.repository.FollowerRepository;
import com.watchwise.watchwise_api.settings.dto.*;
import com.watchwise.watchwise_api.user.entity.User;
import com.watchwise.watchwise_api.user.repository.UserRepository;
import com.watchwise.watchwise_api.userblock.entity.UserBlock;
import com.watchwise.watchwise_api.userblock.repository.UserBlockRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SettingsViewServiceImplTest {
    @Mock private UserRepository userRepository;
    @Mock private FollowerRepository followerRepository;
    @Mock private UserBlockRepository userBlockRepository;
    @InjectMocks private SettingsViewServiceImpl service;

    private final UUID ownerId = UUID.randomUUID();
    private final PageRequest previewPage = PageRequest.of(0, 10,
            Sort.by(Sort.Direction.DESC, "createdAt").and(Sort.by(Sort.Direction.DESC, "id")));

    @Test
    void shouldAssembleOnlySettingsFieldsWithBoundedPreviewsAndFullCounts() {
        User owner = owner(false);
        when(userRepository.findById(ownerId)).thenReturn(Optional.of(owner));
        when(followerRepository.countByFollowedIdAndStatus(ownerId, FollowStatus.ACCEPTED)).thenReturn(13L);
        when(followerRepository.countByFollowerIdAndStatus(ownerId, FollowStatus.ACCEPTED)).thenReturn(17L);
        List<User> previews = IntStream.range(0, 10).mapToObj(i -> User.builder()
                .id(UUID.randomUUID()).username("preview" + i).name("Preview " + i)
                .profilePicture("https://example.com/" + i).email("secret@email.com")
                .password("secret").build()).toList();
        when(followerRepository.findByFollowedIdAndStatus(ownerId, FollowStatus.PENDING, previewPage))
                .thenReturn(new PageImpl<>(previews.stream().map(user -> Follower.builder()
                        .follower(user).followed(owner).status(FollowStatus.PENDING).build()).toList(), previewPage, 11));
        when(userBlockRepository.findByBlockerIdOrderByCreatedAtDescIdDesc(ownerId, previewPage))
                .thenReturn(new PageImpl<>(previews.stream().map(user -> UserBlock.builder()
                        .blocker(owner).blocked(user).build()).toList(), previewPage, 11));

        SettingsViewDTO result = service.getSettings(ownerId);

        assertThat(result).isNotNull();
        assertThat(result.profile()).isEqualTo(new SettingsProfileDTO(ownerId, "owner", "Owner Name", "Bio",
                "https://example.com/avatar", "https://example.com/banner", false));
        assertThat(result.account()).isEqualTo(new SettingsAccountDTO("owner@email.com"));
        assertThat(result.contentPreferences()).isEqualTo(new SettingsContentPreferencesDTO("pt-BR", "BR"));
        assertThat(result.privacy().followersCount()).isEqualTo(13);
        assertThat(result.privacy().followingCount()).isEqualTo(17);
        assertThat(result.privacy().pendingFollowRequestsCount()).isEqualTo(11);
        assertThat(result.privacy().blockedUsersCount()).isEqualTo(11);
        assertThat(result.privacy().pendingFollowRequests()).hasSize(10);
        assertThat(result.privacy().blockedUsers()).hasSize(10);
        assertThat(result.privacy().pendingFollowRequests()).extracting(SettingsUserPreviewDTO::username)
                .containsExactlyElementsOf(previews.stream().map(User::getUsername).toList());
        assertThat(result.privacy().blockedUsers()).extracting(SettingsUserPreviewDTO::id)
                .containsExactlyElementsOf(previews.stream().map(User::getId).toList());
        assertThat(result.privacy().blockedUsers().getFirst()).isEqualTo(new SettingsUserPreviewDTO(
                previews.getFirst().getId(), "preview0", "Preview 0", "https://example.com/0"));
        verify(userRepository, times(1)).findById(ownerId);
        verifyNoMoreInteractions(userRepository);
    }

    @Test
    void shouldSkipPendingQueriesForPublicProfilesAndReturnEmptySections() {
        when(userRepository.findById(ownerId)).thenReturn(Optional.of(owner(true)));
        when(userBlockRepository.findByBlockerIdOrderByCreatedAtDescIdDesc(ownerId, previewPage))
                .thenReturn(Page.empty(previewPage));

        SettingsViewDTO result = service.getSettings(ownerId);

        assertThat(result).isNotNull();
        assertThat(result.profile().isProfilePublic()).isTrue();
        assertThat(result.privacy().pendingFollowRequestsCount()).isZero();
        assertThat(result.privacy().pendingFollowRequests()).isEmpty();
        assertThat(result.privacy().blockedUsersCount()).isZero();
        assertThat(result.privacy().blockedUsers()).isEmpty();
        verify(followerRepository).countByFollowedIdAndStatus(ownerId, FollowStatus.ACCEPTED);
        verify(followerRepository).countByFollowerIdAndStatus(ownerId, FollowStatus.ACCEPTED);
        verifyNoMoreInteractions(followerRepository);
    }

    @Test
    void shouldRejectMissingOwnerBeforeReadingSocialData() {
        when(userRepository.findById(ownerId)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.getSettings(ownerId))
                .isInstanceOf(NotFoundException.class).hasMessage("User not found");
        verifyNoInteractions(followerRepository, userBlockRepository);
    }

    @Test
    void shouldExposeOnlyAllowlistedDtoFields() {
        assertFields(SettingsViewDTO.class, "profile", "privacy", "contentPreferences", "account");
        assertFields(SettingsProfileDTO.class, "id", "username", "name", "description", "profilePicture", "banner", "isProfilePublic");
        assertFields(SettingsPrivacyDTO.class, "followersCount", "followingCount", "pendingFollowRequestsCount",
                "pendingFollowRequests", "blockedUsersCount", "blockedUsers");
        assertFields(SettingsContentPreferencesDTO.class, "preferredLanguage", "preferredRegion");
        assertFields(SettingsAccountDTO.class, "email");
        assertFields(SettingsUserPreviewDTO.class, "id", "username", "name", "profilePicture");
    }

    private void assertFields(Class<?> type, String... names) {
        assertThat(Arrays.stream(type.getRecordComponents()).map(component -> component.getName()))
                .containsExactly(names);
    }

    private User owner(boolean publicProfile) {
        return User.builder().id(ownerId).username("owner").name("Owner Name").email("owner@email.com")
                .password("secret").description("Bio").profilePicture("https://example.com/avatar")
                .banner("https://example.com/banner").isProfilePublic(publicProfile)
                .preferredLanguage("pt-BR").preferredRegion("BR").build();
    }
}
