package com.watchwise.watchwise_api.notification.service.impl;

import com.watchwise.watchwise_api.common.tmdb.TmdbClient;
import com.watchwise.watchwise_api.common.tmdb.TmdbCredit;
import com.watchwise.watchwise_api.common.tmdb.TmdbPersonCredits;
import com.watchwise.watchwise_api.common.transaction.NewTransactionExecutor;
import com.watchwise.watchwise_api.content.dto.ContentRefDTO;
import com.watchwise.watchwise_api.content.entity.Content;
import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.content.repository.ContentRepository;
import com.watchwise.watchwise_api.content.service.ContentService;
import com.watchwise.watchwise_api.followedperson.repository.FollowedPersonRepository;
import com.watchwise.watchwise_api.notification.entity.Notification;
import com.watchwise.watchwise_api.notification.entity.NotificationType;
import com.watchwise.watchwise_api.notification.entity.TrackedPersonCredit;
import com.watchwise.watchwise_api.notification.entity.TrackedPersonState;
import com.watchwise.watchwise_api.notification.repository.NotificationRepository;
import com.watchwise.watchwise_api.notification.repository.TrackedPersonCreditRepository;
import com.watchwise.watchwise_api.notification.repository.TrackedPersonStateRepository;
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

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FollowedPersonTrackingServiceImplTest {

    @Mock private FollowedPersonRepository followedPersonRepository;
    @Mock private TrackedPersonStateRepository trackedPersonStateRepository;
    @Mock private TrackedPersonCreditRepository trackedPersonCreditRepository;
    @Mock private NotificationRepository notificationRepository;
    @Mock private UserRepository userRepository;
    @Mock private ContentService contentService;
    @Mock private ContentRepository contentRepository;
    @Mock private TmdbClient tmdbClient;
    @Mock private NewTransactionExecutor newTransactionExecutor;

    @InjectMocks
    private FollowedPersonTrackingServiceImpl followedPersonTrackingService;

    @Captor
    private ArgumentCaptor<Notification> notificationCaptor;

    @Captor
    private ArgumentCaptor<TrackedPersonCredit> trackedPersonCreditCaptor;

    private UUID followerId;

    @BeforeEach
    void setUp() {
        followerId = UUID.randomUUID();
        lenient().when(newTransactionExecutor.runInNewTransaction(any()))
                .thenAnswer(invocation -> ((Supplier<?>) invocation.getArgument(0)).get());
    }

    @Test
    @DisplayName("[trackFollowedPeopleCredits] Should Notify Followers Of A New Credit - When A Credit Not Seen Before Appears On A Later Run")
    void shouldNotifyFollowersOfANewCreditWhenACreditNotSeenBeforeAppearsOnALaterRun() {
        when(followedPersonRepository.findDistinctPersonTmdbIds()).thenReturn(List.of("6193"));
        TrackedPersonState state = TrackedPersonState.builder().id(UUID.randomUUID()).personTmdbId("6193").build();
        when(trackedPersonStateRepository.findByPersonTmdbId("6193")).thenReturn(Optional.of(state));
        when(tmdbClient.getPersonCombinedCredits("6193")).thenReturn(Optional.of(
                new TmdbPersonCredits(List.of(new TmdbCredit("603", "movie")), List.of())));
        when(trackedPersonCreditRepository.existsByTrackedPersonStateIdAndCreditTmdbId(state.getId(), "603")).thenReturn(false);
        ContentRefDTO contentRef = new ContentRefDTO(UUID.randomUUID(), "603", ContentType.MOVIE, null, null, null, null, null, null, null);
        when(contentService.getOrCreateReference(any())).thenReturn(contentRef);
        Content content = Content.builder().id(contentRef.id()).tmdbId("603").type(ContentType.MOVIE).build();
        when(contentRepository.getReferenceById(contentRef.id())).thenReturn(content);
        when(followedPersonRepository.findUserIdsByPersonTmdbId("6193")).thenReturn(List.of(followerId));

        followedPersonTrackingService.trackFollowedPeopleCredits();

        verify(notificationRepository).save(notificationCaptor.capture());
        assertThat(notificationCaptor.getValue().getType()).isEqualTo(NotificationType.FOLLOWED_PERSON_NEW_CREDIT);
        assertThat(notificationCaptor.getValue().getPersonTmdbId()).isEqualTo("6193");
        assertThat(notificationCaptor.getValue().getContent()).isEqualTo(content);
    }

    @Test
    @DisplayName("[trackFollowedPeopleCredits] Should Not Notify - When The Credit Was Already Seen Before")
    void shouldNotNotifyWhenTheCreditWasAlreadySeenBefore() {
        when(followedPersonRepository.findDistinctPersonTmdbIds()).thenReturn(List.of("6193"));
        TrackedPersonState state = TrackedPersonState.builder().id(UUID.randomUUID()).personTmdbId("6193").build();
        when(trackedPersonStateRepository.findByPersonTmdbId("6193")).thenReturn(Optional.of(state));
        when(tmdbClient.getPersonCombinedCredits("6193")).thenReturn(Optional.of(
                new TmdbPersonCredits(List.of(new TmdbCredit("603", "movie")), List.of())));
        when(trackedPersonCreditRepository.existsByTrackedPersonStateIdAndCreditTmdbId(state.getId(), "603")).thenReturn(true);

        followedPersonTrackingService.trackFollowedPeopleCredits();

        verify(notificationRepository, never()).save(any());
        verify(contentService, never()).getOrCreateReference(any());
    }

    @Test
    @DisplayName("[trackFollowedPeopleCredits] Should Create A TrackedPersonState - When The Person Has Never Been Checked Before")
    void shouldCreateATrackedPersonStateWhenThePersonHasNeverBeenCheckedBefore() {
        when(followedPersonRepository.findDistinctPersonTmdbIds()).thenReturn(List.of("6193"));
        when(trackedPersonStateRepository.findByPersonTmdbId("6193")).thenReturn(Optional.empty());
        when(tmdbClient.getPersonCombinedCredits("6193")).thenReturn(Optional.of(new TmdbPersonCredits(List.of(), List.of())));
        when(trackedPersonStateRepository.save(any(TrackedPersonState.class)))
                .thenAnswer(invocation -> {
                    TrackedPersonState saved = invocation.getArgument(0);
                    saved.setId(UUID.randomUUID());
                    return saved;
                });

        followedPersonTrackingService.trackFollowedPeopleCredits();

        ArgumentCaptor<TrackedPersonState> stateCaptor = ArgumentCaptor.forClass(TrackedPersonState.class);
        verify(trackedPersonStateRepository).save(stateCaptor.capture());
        assertThat(stateCaptor.getValue().getPersonTmdbId()).isEqualTo("6193");
    }

    @Test
    @DisplayName("[trackFollowedPeopleCredits] Should Skip The Person - When TMDB Returns Nothing")
    void shouldSkipThePersonWhenTmdbReturnsNothing() {
        when(followedPersonRepository.findDistinctPersonTmdbIds()).thenReturn(List.of("6193"));
        when(tmdbClient.getPersonCombinedCredits("6193")).thenReturn(Optional.empty());

        followedPersonTrackingService.trackFollowedPeopleCredits();

        verify(trackedPersonStateRepository, never()).findByPersonTmdbId(any());
        verify(notificationRepository, never()).save(any());
    }

    @Test
    @DisplayName("[trackFollowedPeopleCredits] Should Persist All Pre-Existing Credits Without Notifying - When The Person Has Never Been Checked Before")
    void shouldPersistAllPreExistingCreditsWithoutNotifyingWhenThePersonHasNeverBeenCheckedBefore() {
        when(followedPersonRepository.findDistinctPersonTmdbIds()).thenReturn(List.of("6193"));
        when(trackedPersonStateRepository.findByPersonTmdbId("6193")).thenReturn(Optional.empty());
        UUID generatedStateId = UUID.randomUUID();
        when(trackedPersonStateRepository.save(any(TrackedPersonState.class)))
                .thenAnswer(invocation -> {
                    TrackedPersonState saved = invocation.getArgument(0);
                    saved.setId(generatedStateId);
                    return saved;
                });
        when(tmdbClient.getPersonCombinedCredits("6193")).thenReturn(Optional.of(new TmdbPersonCredits(
                List.of(new TmdbCredit("603", "movie"), new TmdbCredit("604", "movie")),
                List.of(new TmdbCredit("1399", "tv")))));
        when(trackedPersonCreditRepository.existsByTrackedPersonStateIdAndCreditTmdbId(eq(generatedStateId), any()))
                .thenReturn(false);
        when(contentService.getOrCreateReference(argThat(dto -> dto != null && "603".equals(dto.tmdbId()))))
                .thenReturn(new ContentRefDTO(UUID.randomUUID(), "603", ContentType.MOVIE, null, null, null, null, null, null, null));
        when(contentService.getOrCreateReference(argThat(dto -> dto != null && "604".equals(dto.tmdbId()))))
                .thenReturn(new ContentRefDTO(UUID.randomUUID(), "604", ContentType.MOVIE, null, null, null, null, null, null, null));
        when(contentService.getOrCreateReference(argThat(dto -> dto != null && "1399".equals(dto.tmdbId()))))
                .thenReturn(new ContentRefDTO(UUID.randomUUID(), "1399", ContentType.SERIES, null, null, null, null, null, null, null));

        followedPersonTrackingService.trackFollowedPeopleCredits();

        verify(trackedPersonCreditRepository, times(3)).save(any(TrackedPersonCredit.class));
        verify(notificationRepository, never()).save(any());
        verify(followedPersonRepository, never()).findUserIdsByPersonTmdbId(any());
        verify(contentRepository, never()).getReferenceById(any());
    }

    @Test
    @DisplayName("[trackFollowedPeopleCredits] Should Notify Exactly Once - When A Second Run Finds One Genuinely New Credit")
    void shouldNotifyExactlyOnceWhenASecondRunFindsOneGenuinelyNewCredit() {
        when(followedPersonRepository.findDistinctPersonTmdbIds()).thenReturn(List.of("6193"));
        TrackedPersonState existingState = TrackedPersonState.builder().id(UUID.randomUUID()).personTmdbId("6193").build();
        when(trackedPersonStateRepository.findByPersonTmdbId("6193")).thenReturn(Optional.of(existingState));
        when(tmdbClient.getPersonCombinedCredits("6193")).thenReturn(Optional.of(new TmdbPersonCredits(
                List.of(new TmdbCredit("603", "movie"), new TmdbCredit("605", "movie")), List.of())));
        when(trackedPersonCreditRepository.existsByTrackedPersonStateIdAndCreditTmdbId(existingState.getId(), "603")).thenReturn(true);
        when(trackedPersonCreditRepository.existsByTrackedPersonStateIdAndCreditTmdbId(existingState.getId(), "605")).thenReturn(false);
        ContentRefDTO newCreditRef = new ContentRefDTO(UUID.randomUUID(), "605", ContentType.MOVIE, null, null, null, null, null, null, null);
        when(contentService.getOrCreateReference(argThat(dto -> "605".equals(dto.tmdbId())))).thenReturn(newCreditRef);
        Content newCreditContent = Content.builder().id(newCreditRef.id()).tmdbId("605").type(ContentType.MOVIE).build();
        when(contentRepository.getReferenceById(newCreditRef.id())).thenReturn(newCreditContent);
        when(followedPersonRepository.findUserIdsByPersonTmdbId("6193")).thenReturn(List.of(followerId));

        followedPersonTrackingService.trackFollowedPeopleCredits();

        verify(notificationRepository, times(1)).save(notificationCaptor.capture());
        assertThat(notificationCaptor.getValue().getContent()).isEqualTo(newCreditContent);
        verify(trackedPersonCreditRepository, times(1)).save(any(TrackedPersonCredit.class));
    }

    @Test
    @DisplayName("[trackFollowedPeopleCredits] Should Persist TrackedPersonCredit In The Same Run - When The Person Has Never Been Checked Before")
    void shouldPersistTrackedPersonCreditInTheSameRunWhenThePersonHasNeverBeenCheckedBefore() {
        when(followedPersonRepository.findDistinctPersonTmdbIds()).thenReturn(List.of("6193"));
        when(trackedPersonStateRepository.findByPersonTmdbId("6193")).thenReturn(Optional.empty());
        when(tmdbClient.getPersonCombinedCredits("6193")).thenReturn(Optional.of(
                new TmdbPersonCredits(List.of(new TmdbCredit("603", "movie")), List.of())));
        UUID generatedStateId = UUID.randomUUID();
        when(trackedPersonStateRepository.save(any(TrackedPersonState.class)))
                .thenAnswer(invocation -> {
                    TrackedPersonState saved = invocation.getArgument(0);
                    saved.setId(generatedStateId);
                    return saved;
                });
        when(trackedPersonCreditRepository.existsByTrackedPersonStateIdAndCreditTmdbId(generatedStateId, "603")).thenReturn(false);
        ContentRefDTO contentRef = new ContentRefDTO(UUID.randomUUID(), "603", ContentType.MOVIE, null, null, null, null, null, null, null);
        when(contentService.getOrCreateReference(any())).thenReturn(contentRef);

        followedPersonTrackingService.trackFollowedPeopleCredits();

        verify(trackedPersonCreditRepository).save(trackedPersonCreditCaptor.capture());
        assertThat(trackedPersonCreditCaptor.getValue().getCreditTmdbId()).isEqualTo("603");
        assertThat(trackedPersonCreditCaptor.getValue().getCreditType()).isEqualTo(ContentType.MOVIE);
        assertThat(trackedPersonCreditCaptor.getValue().getTrackedPersonState().getId()).isEqualTo(generatedStateId);
    }
}
