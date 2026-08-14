package com.watchwise.watchwise_api.followedperson.controller;

import com.watchwise.watchwise_api.common.dto.PageResponseDTO;
import com.watchwise.watchwise_api.common.security.RequestThrottler;
import com.watchwise.watchwise_api.followedperson.service.FollowedPersonService;
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

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FollowedPersonControllerTest {

    @Mock
    private FollowedPersonService followedPersonService;

    @Mock
    private RequestThrottler requestThrottler;

    @InjectMocks
    private FollowedPersonController followedPersonController;

    private UUID currentUserId;
    private String personTmdbId;

    @BeforeEach
    void setUp() {
        currentUserId = UUID.randomUUID();
        personTmdbId = "123";

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(currentUserId, null, List.of())
        );
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("[followPerson] Should Return NoContent And Delegate To Service - When Called")
    void shouldReturnNoContentAndDelegateToServiceWhenCalledForFollow() {
        ResponseEntity<Void> result = followedPersonController.followPerson(personTmdbId);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        verify(followedPersonService).followPerson(currentUserId, personTmdbId);
    }

    @Test
    @DisplayName("[unfollowPerson] Should Return NoContent And Delegate To Service - When Called")
    void shouldReturnNoContentAndDelegateToServiceWhenCalledForUnfollow() {
        ResponseEntity<Void> result = followedPersonController.unfollowPerson(personTmdbId);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        verify(followedPersonService).unfollowPerson(currentUserId, personTmdbId);
    }

    @Test
    @DisplayName("[getFollowedPeople] Should Return Page Envelope With Content And Metadata - When Service Returns Results")
    void shouldReturnPageEnvelopeWithContentAndMetadataWhenServiceReturnsResults() {
        UUID targetUserId = UUID.randomUUID();
        Page<String> page = new PageImpl<>(List.of(personTmdbId), PageRequest.of(0, 10), 1);
        when(followedPersonService.getFollowedPeople(currentUserId, targetUserId, 1, 10)).thenReturn(page);

        ResponseEntity<PageResponseDTO<String>> result = followedPersonController.getFollowedPeople(targetUserId, 1, 10);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(result.getBody().content()).containsExactly(personTmdbId);
        assertThat(result.getBody().page()).isEqualTo(1);
        assertThat(result.getBody().totalElements()).isEqualTo(1);
        assertThat(result.getBody().hasNext()).isFalse();
    }

    @Test
    @DisplayName("[getFollowedPeople] Should Return Empty Content - When Service Returns No Results")
    void shouldReturnEmptyContentWhenServiceReturnsNoResults() {
        UUID targetUserId = UUID.randomUUID();
        when(followedPersonService.getFollowedPeople(currentUserId, targetUserId, null, null)).thenReturn(Page.empty());

        ResponseEntity<PageResponseDTO<String>> result = followedPersonController.getFollowedPeople(targetUserId, null, null);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(result.getBody().content()).isEmpty();
    }

    @Test
    @DisplayName("[getFollowedPeople] Should Resolve Viewer Id From The Security Context - When Called")
    void shouldResolveViewerIdFromTheSecurityContextWhenGettingFollowedPeople() {
        UUID targetUserId = UUID.randomUUID();
        when(followedPersonService.getFollowedPeople(currentUserId, targetUserId, 1, 10)).thenReturn(Page.empty());

        followedPersonController.getFollowedPeople(targetUserId, 1, 10);

        verify(followedPersonService).getFollowedPeople(currentUserId, targetUserId, 1, 10);
    }
}