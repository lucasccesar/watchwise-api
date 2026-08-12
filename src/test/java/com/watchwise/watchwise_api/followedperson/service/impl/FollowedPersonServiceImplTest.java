package com.watchwise.watchwise_api.followedperson.service.impl;

import com.watchwise.watchwise_api.followedperson.entity.FollowedPerson;
import com.watchwise.watchwise_api.followedperson.repository.FollowedPersonRepository;
import com.watchwise.watchwise_api.user.entity.User;
import com.watchwise.watchwise_api.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FollowedPersonServiceImplTest {

    @Mock
    private FollowedPersonRepository followedPersonRepository;

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private FollowedPersonServiceImpl followedPersonService;

    @Captor
    private ArgumentCaptor<FollowedPerson> followedPersonCaptor;

    private UUID userId;
    private User user;
    private String personTmdbId;

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
        personTmdbId = "123";

        user = User.builder()
                .id(userId)
                .username("lucas")
                .email("lucas@email.com")
                .password("hashed_password")
                .isProfilePublic(true)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
    }

    @Test
    @DisplayName("[followPerson] Should Save New FollowedPerson - When Not Already Following")
    void shouldSaveNewFollowedPersonWhenNotAlreadyFollowing() {
        when(followedPersonRepository.existsByUserIdAndPersonTmdbId(userId, personTmdbId)).thenReturn(false);
        when(userRepository.getReferenceById(userId)).thenReturn(user);

        followedPersonService.followPerson(userId, personTmdbId);

        verify(followedPersonRepository).save(followedPersonCaptor.capture());
        assertThat(followedPersonCaptor.getValue().getUser()).isEqualTo(user);
        assertThat(followedPersonCaptor.getValue().getPersonTmdbId()).isEqualTo(personTmdbId);
        assertThat(followedPersonCaptor.getValue().getCreatedAt()).isNotNull();
    }

    @Test
    @DisplayName("[followPerson] Should Do Nothing - When Already Following That Person")
    void shouldDoNothingWhenAlreadyFollowingThatPerson() {
        when(followedPersonRepository.existsByUserIdAndPersonTmdbId(userId, personTmdbId)).thenReturn(true);

        followedPersonService.followPerson(userId, personTmdbId);

        verify(followedPersonRepository, never()).save(any());
        verify(userRepository, never()).getReferenceById(any());
    }

    @Test
    @DisplayName("[followPerson] Should Resolve Successfully - When Save Throws DataIntegrityViolationException But Row Now Exists")
    void shouldResolveSuccessfullyWhenSaveThrowsDataIntegrityViolationExceptionButRowNowExists() {
        when(followedPersonRepository.existsByUserIdAndPersonTmdbId(userId, personTmdbId))
                .thenReturn(false)
                .thenReturn(true);
        when(userRepository.getReferenceById(userId)).thenReturn(user);
        when(followedPersonRepository.save(any(FollowedPerson.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate"));

        assertThatCode(() -> followedPersonService.followPerson(userId, personTmdbId))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("[followPerson] Should Rethrow DataIntegrityViolationException - When Row Still Does Not Exist After Save Fails")
    void shouldRethrowDataIntegrityViolationExceptionWhenRowStillDoesNotExistAfterSaveFails() {
        when(followedPersonRepository.existsByUserIdAndPersonTmdbId(userId, personTmdbId)).thenReturn(false);
        when(userRepository.getReferenceById(userId)).thenReturn(user);
        DataIntegrityViolationException exception = new DataIntegrityViolationException("unexpected db error");
        when(followedPersonRepository.save(any(FollowedPerson.class))).thenThrow(exception);

        assertThatThrownBy(() -> followedPersonService.followPerson(userId, personTmdbId))
                .isSameAs(exception);
    }

    @Test
    @DisplayName("[unfollowPerson] Should Delete The Row - When It Exists")
    void shouldDeleteTheRowWhenItExists() {
        FollowedPerson followedPerson = FollowedPerson.builder()
                .id(UUID.randomUUID())
                .user(user)
                .personTmdbId(personTmdbId)
                .createdAt(LocalDateTime.now())
                .build();
        when(followedPersonRepository.findByUserIdAndPersonTmdbId(userId, personTmdbId))
                .thenReturn(Optional.of(followedPerson));

        followedPersonService.unfollowPerson(userId, personTmdbId);

        verify(followedPersonRepository).delete(followedPerson);
    }

    @Test
    @DisplayName("[unfollowPerson] Should Do Nothing - When The Row Does Not Exist")
    void shouldDoNothingWhenTheRowDoesNotExist() {
        when(followedPersonRepository.findByUserIdAndPersonTmdbId(userId, personTmdbId))
                .thenReturn(Optional.empty());

        followedPersonService.unfollowPerson(userId, personTmdbId);

        verify(followedPersonRepository, never()).delete(any());
    }
}
