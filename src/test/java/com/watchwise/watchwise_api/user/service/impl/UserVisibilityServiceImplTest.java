package com.watchwise.watchwise_api.user.service.impl;

import com.watchwise.watchwise_api.common.exception.ForbiddenException;
import com.watchwise.watchwise_api.common.exception.NotFoundException;
import com.watchwise.watchwise_api.follower.entity.FollowStatus;
import com.watchwise.watchwise_api.follower.repository.FollowerRepository;
import com.watchwise.watchwise_api.user.entity.User;
import com.watchwise.watchwise_api.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserVisibilityServiceImplTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private FollowerRepository followerRepository;

    private UserVisibilityServiceImpl userVisibilityService;

    @BeforeEach
    void setUp() {
        userVisibilityService = new UserVisibilityServiceImpl(userRepository, followerRepository);
    }

    @Test
    @DisplayName("[assertCanView] Should Throw NotFoundException - When Target User Does Not Exist")
    void shouldThrowNotFoundExceptionWhenTargetUserDoesNotExist() {
        UUID viewerId = UUID.randomUUID();
        UUID targetUserId = UUID.randomUUID();
        when(userRepository.findById(targetUserId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userVisibilityService.assertCanView(viewerId, targetUserId))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("User not found");

        verify(followerRepository, never()).existsByFollowerIdAndFollowedIdAndStatus(viewerId, targetUserId, FollowStatus.ACCEPTED);
    }

    @Test
    @DisplayName("[assertCanView] Should Allow Access - When Viewer Is The Target User")
    void shouldAllowAccessWhenViewerIsTheTargetUser() {
        UUID userId = UUID.randomUUID();
        when(userRepository.findById(userId)).thenReturn(Optional.of(user(userId, false)));

        userVisibilityService.assertCanView(userId, userId);

        verify(followerRepository, never()).existsByFollowerIdAndFollowedIdAndStatus(userId, userId, FollowStatus.ACCEPTED);
    }

    @Test
    @DisplayName("[assertCanView] Should Allow Access - When Target Profile Is Public")
    void shouldAllowAccessWhenTargetProfileIsPublic() {
        UUID viewerId = UUID.randomUUID();
        UUID targetUserId = UUID.randomUUID();
        when(userRepository.findById(targetUserId)).thenReturn(Optional.of(user(targetUserId, true)));

        userVisibilityService.assertCanView(viewerId, targetUserId);

        verify(followerRepository, never()).existsByFollowerIdAndFollowedIdAndStatus(viewerId, targetUserId, FollowStatus.ACCEPTED);
    }

    @Test
    @DisplayName("[assertCanView] Should Allow Access - When Viewer Is An Accepted Follower")
    void shouldAllowAccessWhenViewerIsAnAcceptedFollower() {
        UUID viewerId = UUID.randomUUID();
        UUID targetUserId = UUID.randomUUID();
        when(userRepository.findById(targetUserId)).thenReturn(Optional.of(user(targetUserId, false)));
        when(followerRepository.existsByFollowerIdAndFollowedIdAndStatus(viewerId, targetUserId, FollowStatus.ACCEPTED))
                .thenReturn(true);

        userVisibilityService.assertCanView(viewerId, targetUserId);

        verify(followerRepository).existsByFollowerIdAndFollowedIdAndStatus(viewerId, targetUserId, FollowStatus.ACCEPTED);
    }

    @Test
    @DisplayName("[assertCanView] Should Throw ForbiddenException - When Target Profile Is Private And Viewer Is Not Accepted")
    void shouldThrowForbiddenExceptionWhenTargetProfileIsPrivateAndViewerIsNotAccepted() {
        UUID viewerId = UUID.randomUUID();
        UUID targetUserId = UUID.randomUUID();
        when(userRepository.findById(targetUserId)).thenReturn(Optional.of(user(targetUserId, false)));
        when(followerRepository.existsByFollowerIdAndFollowedIdAndStatus(viewerId, targetUserId, FollowStatus.ACCEPTED))
                .thenReturn(false);

        assertThatThrownBy(() -> userVisibilityService.assertCanView(viewerId, targetUserId))
                .isInstanceOf(ForbiddenException.class)
                .hasMessage("This user profile is private");
    }

    private User user(UUID id, boolean isProfilePublic) {
        return User.builder().id(id).isProfilePublic(isProfilePublic).build();
    }
}
