package com.watchwise.watchwise_api.follower.controller;

import com.watchwise.watchwise_api.common.dto.PageResponseDTO;
import com.watchwise.watchwise_api.common.security.RequestThrottler;
import com.watchwise.watchwise_api.follower.dto.FollowStatusResponseDTO;
import com.watchwise.watchwise_api.follower.entity.FollowStatus;
import com.watchwise.watchwise_api.follower.service.FollowerService;
import com.watchwise.watchwise_api.user.dto.PublicUserDTO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FollowerControllerTest {

    @Mock
    private FollowerService followerService;

    @Mock
    private RequestThrottler requestThrottler;

    @InjectMocks
    private FollowerController followerController;

    private UUID currentUserId;
    private UUID targetUserId;
    private PublicUserDTO publicUserDTO;

    @BeforeEach
    void setUp() {
        currentUserId = UUID.randomUUID();
        targetUserId = UUID.randomUUID();
        publicUserDTO = new PublicUserDTO(
                UUID.randomUUID(),
                "JaneDoe",
                "Some description",
                "https://picture.com/pic.png",
                true,
                LocalDateTime.now()
        );

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(currentUserId, null, List.of())
        );
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("[getFollowers] Should Return Page Envelope With Content And Metadata - When Service Returns Results")
    void shouldReturnPageEnvelopeWithContentAndMetadataWhenServiceReturnsFollowers() {
        Page<PublicUserDTO> page = new PageImpl<>(List.of(publicUserDTO), PageRequest.of(0, 10), 1);
        when(followerService.getFollowers(currentUserId, targetUserId, 1, 10)).thenReturn(page);

        ResponseEntity<PageResponseDTO<PublicUserDTO>> result = followerController.getFollowers(targetUserId, 1, 10);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(result.getBody().content()).containsExactly(publicUserDTO);
        assertThat(result.getBody().page()).isEqualTo(1);
        assertThat(result.getBody().totalElements()).isEqualTo(1);
        assertThat(result.getBody().hasNext()).isFalse();
    }

    @Test
    @DisplayName("[getFollowers] Should Return Empty Content - When Service Returns No Followers")
    void shouldReturnEmptyContentWhenServiceReturnsNoFollowers() {
        when(followerService.getFollowers(currentUserId, targetUserId, null, null)).thenReturn(Page.empty());

        ResponseEntity<PageResponseDTO<PublicUserDTO>> result = followerController.getFollowers(targetUserId, null, null);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(result.getBody().content()).isEmpty();
    }

    @Test
    @DisplayName("[getFollowers] Should Resolve Viewer Id From The Security Context - When Called")
    void shouldResolveViewerIdFromTheSecurityContextWhenGettingFollowers() {
        when(followerService.getFollowers(currentUserId, targetUserId, 1, 10)).thenReturn(Page.empty());

        followerController.getFollowers(targetUserId, 1, 10);

        verify(followerService).getFollowers(currentUserId, targetUserId, 1, 10);
    }

    @Test
    @DisplayName("[getFollowing] Should Return Page Envelope With Content And Metadata - When Service Returns Results")
    void shouldReturnPageEnvelopeWithContentAndMetadataWhenServiceReturnsFollowing() {
        Page<PublicUserDTO> page = new PageImpl<>(List.of(publicUserDTO), PageRequest.of(0, 10), 1);
        when(followerService.getFollowing(currentUserId, targetUserId, 1, 10)).thenReturn(page);

        ResponseEntity<PageResponseDTO<PublicUserDTO>> result = followerController.getFollowing(targetUserId, 1, 10);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(result.getBody().content()).containsExactly(publicUserDTO);
        assertThat(result.getBody().page()).isEqualTo(1);
        assertThat(result.getBody().totalElements()).isEqualTo(1);
        assertThat(result.getBody().hasNext()).isFalse();
    }

    @Test
    @DisplayName("[getFollowing] Should Return Empty Content - When Service Returns No Following")
    void shouldReturnEmptyContentWhenServiceReturnsNoFollowing() {
        when(followerService.getFollowing(currentUserId, targetUserId, null, null)).thenReturn(Page.empty());

        ResponseEntity<PageResponseDTO<PublicUserDTO>> result = followerController.getFollowing(targetUserId, null, null);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(result.getBody().content()).isEmpty();
    }

    @Test
    @DisplayName("[getFollowing] Should Resolve Viewer Id From The Security Context - When Called")
    void shouldResolveViewerIdFromTheSecurityContextWhenGettingFollowing() {
        when(followerService.getFollowing(currentUserId, targetUserId, 1, 10)).thenReturn(Page.empty());

        followerController.getFollowing(targetUserId, 1, 10);

        verify(followerService).getFollowing(currentUserId, targetUserId, 1, 10);
    }

    @Test
    @DisplayName("[followUser] Should Return Accepted Status In Body - When Service Returns Accepted")
    void shouldReturnAcceptedStatusInBodyWhenServiceReturnsAccepted() {
        when(followerService.followUser(currentUserId, targetUserId)).thenReturn(FollowStatus.ACCEPTED);

        ResponseEntity<FollowStatusResponseDTO> result = followerController.followUser(targetUserId);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(result.getBody()).isEqualTo(new FollowStatusResponseDTO(FollowStatus.ACCEPTED));
    }

    @Test
    @DisplayName("[followUser] Should Return Pending Status In Body - When Service Returns Pending")
    void shouldReturnPendingStatusInBodyWhenServiceReturnsPending() {
        when(followerService.followUser(currentUserId, targetUserId)).thenReturn(FollowStatus.PENDING);

        ResponseEntity<FollowStatusResponseDTO> result = followerController.followUser(targetUserId);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(result.getBody()).isEqualTo(new FollowStatusResponseDTO(FollowStatus.PENDING));
    }

    @Test
    @DisplayName("[followUser] Should Resolve Follower Id From The Security Context - When Called")
    void shouldResolveFollowerIdFromTheSecurityContextWhenFollowingUser() {
        when(followerService.followUser(currentUserId, targetUserId)).thenReturn(FollowStatus.ACCEPTED);

        followerController.followUser(targetUserId);

        verify(followerService).followUser(currentUserId, targetUserId);
    }

    @Test
    @DisplayName("[unfollowUser] Should Return NoContent - When Service Completes Successfully")
    void shouldReturnNoContentWhenUnfollowSucceeds() {
        ResponseEntity<Void> result = followerController.unfollowUser(targetUserId);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    @Test
    @DisplayName("[unfollowUser] Should Resolve Follower Id From The Security Context - When Called")
    void shouldResolveFollowerIdFromTheSecurityContextWhenUnfollowingUser() {
        followerController.unfollowUser(targetUserId);

        verify(followerService).unfollowUser(currentUserId, targetUserId);
    }

    @Test
    @DisplayName("[getPendingFollowRequests] Should Return Page Envelope With Content And Metadata - When Pending Requests Exist")
    void shouldReturnPageEnvelopeWithContentAndMetadataWhenPendingRequestsExist() {
        Page<PublicUserDTO> page = new PageImpl<>(List.of(publicUserDTO), PageRequest.of(0, 10), 1);
        when(followerService.getPendingFollowRequests(currentUserId, 1, 10)).thenReturn(page);

        ResponseEntity<PageResponseDTO<PublicUserDTO>> result = followerController.getPendingFollowRequests(1, 10);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(result.getBody().content()).containsExactly(publicUserDTO);
        assertThat(result.getBody().page()).isEqualTo(1);
        assertThat(result.getBody().totalElements()).isEqualTo(1);
        assertThat(result.getBody().hasNext()).isFalse();
    }

    @Test
    @DisplayName("[getPendingFollowRequests] Should Return Empty Content - When No Pending Requests Exist")
    void shouldReturnEmptyContentWhenNoPendingRequestsExist() {
        when(followerService.getPendingFollowRequests(currentUserId, null, null)).thenReturn(Page.empty());

        ResponseEntity<PageResponseDTO<PublicUserDTO>> result = followerController.getPendingFollowRequests(null, null);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(result.getBody().content()).isEmpty();
    }

    @Test
    @DisplayName("[getPendingFollowRequests] Should Resolve Target User Id From The Security Context - When Called")
    void shouldResolveTargetUserIdFromTheSecurityContextWhenGettingPendingFollowRequests() {
        when(followerService.getPendingFollowRequests(currentUserId, 1, 10)).thenReturn(Page.empty());

        followerController.getPendingFollowRequests(1, 10);

        verify(followerService).getPendingFollowRequests(currentUserId, 1, 10);
    }

    @Test
    @DisplayName("[acceptFollowRequest] Should Return NoContent - When Service Completes Successfully")
    void shouldReturnNoContentWhenAcceptSucceeds() {
        ResponseEntity<Void> result = followerController.acceptFollowRequest(targetUserId);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    @Test
    @DisplayName("[acceptFollowRequest] Should Resolve Target User Id From The Security Context - When Called")
    void shouldResolveTargetUserIdFromTheSecurityContextWhenAcceptingFollowRequest() {
        followerController.acceptFollowRequest(targetUserId);

        verify(followerService).acceptFollowRequest(currentUserId, targetUserId);
    }

    @Test
    @DisplayName("[rejectFollowRequest] Should Return NoContent - When Service Completes Successfully")
    void shouldReturnNoContentWhenRejectSucceeds() {
        ResponseEntity<Void> result = followerController.rejectFollowRequest(targetUserId);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    @Test
    @DisplayName("[rejectFollowRequest] Should Resolve Target User Id From The Security Context - When Called")
    void shouldResolveTargetUserIdFromTheSecurityContextWhenRejectingFollowRequest() {
        followerController.rejectFollowRequest(targetUserId);

        verify(followerService).rejectFollowRequest(currentUserId, targetUserId);
    }
}