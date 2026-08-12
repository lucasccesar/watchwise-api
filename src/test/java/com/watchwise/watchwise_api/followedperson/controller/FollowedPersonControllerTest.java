package com.watchwise.watchwise_api.followedperson.controller;

import com.watchwise.watchwise_api.common.exception.ForbiddenException;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

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
    @DisplayName("[followPerson] Should Return NoContent And Delegate To Service - When UserId Matches The Authenticated User")
    void shouldReturnNoContentAndDelegateToServiceWhenUserIdMatchesTheAuthenticatedUserForFollow() {
        ResponseEntity<Void> result = followedPersonController.followPerson(currentUserId, personTmdbId);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        verify(followedPersonService).followPerson(currentUserId, personTmdbId);
    }

    @Test
    @DisplayName("[followPerson] Should Throw ForbiddenException - When UserId Does Not Match The Authenticated User")
    void shouldThrowForbiddenExceptionWhenUserIdDoesNotMatchTheAuthenticatedUserForFollow() {
        UUID otherUserId = UUID.randomUUID();

        assertThatThrownBy(() -> followedPersonController.followPerson(otherUserId, personTmdbId))
                .isInstanceOf(ForbiddenException.class)
                .hasMessage("Cannot manage another user's followed people");

        verifyNoInteractions(followedPersonService);
    }

    @Test
    @DisplayName("[unfollowPerson] Should Return NoContent And Delegate To Service - When UserId Matches The Authenticated User")
    void shouldReturnNoContentAndDelegateToServiceWhenUserIdMatchesTheAuthenticatedUserForUnfollow() {
        ResponseEntity<Void> result = followedPersonController.unfollowPerson(currentUserId, personTmdbId);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        verify(followedPersonService).unfollowPerson(currentUserId, personTmdbId);
    }

    @Test
    @DisplayName("[unfollowPerson] Should Throw ForbiddenException - When UserId Does Not Match The Authenticated User")
    void shouldThrowForbiddenExceptionWhenUserIdDoesNotMatchTheAuthenticatedUserForUnfollow() {
        UUID otherUserId = UUID.randomUUID();

        assertThatThrownBy(() -> followedPersonController.unfollowPerson(otherUserId, personTmdbId))
                .isInstanceOf(ForbiddenException.class)
                .hasMessage("Cannot manage another user's followed people");

        verifyNoInteractions(followedPersonService);
    }
}