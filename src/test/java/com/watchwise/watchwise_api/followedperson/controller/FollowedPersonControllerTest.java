package com.watchwise.watchwise_api.followedperson.controller;

import com.watchwise.watchwise_api.followedperson.service.FollowedPersonService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class FollowedPersonControllerTest {

    @Mock
    private FollowedPersonService followedPersonService;

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
}