package com.watchwise.watchwise_api.notification.service.impl;

import com.watchwise.watchwise_api.common.tmdb.TmdbClient;
import com.watchwise.watchwise_api.common.tmdb.TmdbCredit;
import com.watchwise.watchwise_api.common.tmdb.TmdbPersonCredits;
import com.watchwise.watchwise_api.common.transaction.AdvisoryLock;
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
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FollowedPersonTrackingServiceImplTest {

    private static final String PERSON_TMDB_ID = "6193";

    @Mock private FollowedPersonRepository followedPersonRepository;
    @Mock private TrackedPersonStateRepository trackedPersonStateRepository;
    @Mock private TrackedPersonCreditRepository trackedPersonCreditRepository;
    @Mock private NotificationRepository notificationRepository;
    @Mock private UserRepository userRepository;
    @Mock private ContentService contentService;
    @Mock private ContentRepository contentRepository;
    @Mock private TmdbClient tmdbClient;
    @Mock private NewTransactionExecutor newTransactionExecutor;
    @Mock private AdvisoryLock advisoryLock;

    @InjectMocks
    private FollowedPersonTrackingServiceImpl followedPersonTrackingService;

    @Captor
    private ArgumentCaptor<List<Notification>> notificationsCaptor;

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
    @DisplayName("[trackFollowedPeopleCredits] Should Notify Followers Of A New Credit - When A Credit Is Not Tracked Yet")
    void shouldNotifyFollowersOfANewCreditWhenACreditIsNotTrackedYet() {
        when(followedPersonRepository.findDistinctPersonTmdbIds()).thenReturn(List.of(PERSON_TMDB_ID));
        TrackedPersonState state = state(PERSON_TMDB_ID);
        when(trackedPersonStateRepository.findByPersonTmdbId(PERSON_TMDB_ID)).thenReturn(Optional.of(state));
        when(tmdbClient.getPersonCombinedCredits(PERSON_TMDB_ID)).thenReturn(Optional.of(
                new TmdbPersonCredits(List.of(new TmdbCredit("603", "movie")), List.of())));
        lenient().when(trackedPersonCreditRepository.findByTrackedPersonStateId(state.getId())).thenReturn(List.of());
        ContentRefDTO contentRef = contentRef("603", ContentType.MOVIE);
        when(contentService.getOrCreateReference(any())).thenReturn(contentRef);
        Content content = content(contentRef);
        when(contentRepository.getReferenceById(contentRef.id())).thenReturn(content);
        when(followedPersonRepository.findUserIdsByPersonTmdbId(PERSON_TMDB_ID)).thenReturn(List.of(followerId));

        followedPersonTrackingService.trackFollowedPeopleCredits();

        InOrder ordering = inOrder(tmdbClient, contentService, advisoryLock);
        ordering.verify(tmdbClient).getPersonCombinedCredits(PERSON_TMDB_ID);
        ordering.verify(contentService).getOrCreateReference(any());
        ordering.verify(advisoryLock).lock("followed-person-tracking:" + PERSON_TMDB_ID);
        verify(notificationRepository).saveAll(notificationsCaptor.capture());
        assertThat(notificationsCaptor.getValue()).hasSize(1);
        Notification notification = notificationsCaptor.getValue().get(0);
        assertThat(notification.getType()).isEqualTo(NotificationType.FOLLOWED_PERSON_NEW_CREDIT);
        assertThat(notification.getPersonTmdbId()).isEqualTo(PERSON_TMDB_ID);
        assertThat(notification.getContent()).isEqualTo(content);
    }

    @Test
    @DisplayName("[trackFollowedPeopleCredits] Should Not Resolve Or Notify A Credit - When Its Typed Key Is Already Stored")
    void shouldNotResolveOrNotifyACreditWhenItsTypedKeyIsAlreadyStored() {
        when(followedPersonRepository.findDistinctPersonTmdbIds()).thenReturn(List.of(PERSON_TMDB_ID));
        TrackedPersonState state = state(PERSON_TMDB_ID);
        when(trackedPersonStateRepository.findByPersonTmdbId(PERSON_TMDB_ID)).thenReturn(Optional.of(state));
        when(tmdbClient.getPersonCombinedCredits(PERSON_TMDB_ID)).thenReturn(Optional.of(
                new TmdbPersonCredits(List.of(new TmdbCredit("603", "movie")), List.of())));
        lenient().when(trackedPersonCreditRepository.findByTrackedPersonStateId(state.getId()))
                .thenReturn(List.of(trackedCredit(state, "603", ContentType.MOVIE)));

        followedPersonTrackingService.trackFollowedPeopleCredits();

        verify(contentService, never()).getOrCreateReference(any());
        verify(trackedPersonCreditRepository, never()).save(any());
        verify(notificationRepository, never()).saveAll(any());
    }

    @Test
    @DisplayName("[trackFollowedPeopleCredits] Should Create A TrackedPersonState - When The Person Has Never Been Checked")
    void shouldCreateATrackedPersonStateWhenThePersonHasNeverBeenChecked() {
        when(followedPersonRepository.findDistinctPersonTmdbIds()).thenReturn(List.of(PERSON_TMDB_ID));
        when(trackedPersonStateRepository.findByPersonTmdbId(PERSON_TMDB_ID)).thenReturn(Optional.empty());
        when(tmdbClient.getPersonCombinedCredits(PERSON_TMDB_ID)).thenReturn(Optional.of(new TmdbPersonCredits(List.of(), List.of())));
        when(trackedPersonStateRepository.save(any(TrackedPersonState.class)))
                .thenAnswer(invocation -> {
                    TrackedPersonState saved = invocation.getArgument(0);
                    saved.setId(UUID.randomUUID());
                    return saved;
                });
        lenient().when(trackedPersonCreditRepository.findByTrackedPersonStateId(any())).thenReturn(List.of());

        followedPersonTrackingService.trackFollowedPeopleCredits();

        verify(trackedPersonStateRepository).save(any(TrackedPersonState.class));
        verify(contentService, never()).getOrCreateReference(any());
        verify(notificationRepository, never()).saveAll(any());
    }

    @Test
    @DisplayName("[trackFollowedPeopleCredits] Should Skip The Person - When TMDB Returns Nothing")
    void shouldSkipThePersonWhenTmdbReturnsNothing() {
        when(followedPersonRepository.findDistinctPersonTmdbIds()).thenReturn(List.of(PERSON_TMDB_ID));
        when(tmdbClient.getPersonCombinedCredits(PERSON_TMDB_ID)).thenReturn(Optional.empty());

        followedPersonTrackingService.trackFollowedPeopleCredits();

        verify(trackedPersonStateRepository, never()).findByPersonTmdbId(any());
        verify(advisoryLock, never()).lock(any());
        verify(notificationRepository, never()).saveAll(any());
    }

    @Test
    @DisplayName("[trackFollowedPeopleCredits] Should Seed Typed IDs Without Content Or Notifications - When Establishing The First Baseline")
    void shouldSeedTypedIdsWithoutContentOrNotificationsWhenEstablishingTheFirstBaseline() {
        when(followedPersonRepository.findDistinctPersonTmdbIds()).thenReturn(List.of(PERSON_TMDB_ID));
        when(trackedPersonStateRepository.findByPersonTmdbId(PERSON_TMDB_ID)).thenReturn(Optional.empty());
        when(trackedPersonStateRepository.save(any(TrackedPersonState.class)))
                .thenAnswer(invocation -> {
                    TrackedPersonState saved = invocation.getArgument(0);
                    saved.setId(UUID.randomUUID());
                    return saved;
                });
        when(tmdbClient.getPersonCombinedCredits(PERSON_TMDB_ID)).thenReturn(Optional.of(new TmdbPersonCredits(
                List.of(new TmdbCredit("123", "movie"), new TmdbCredit("124", "movie")),
                List.of(new TmdbCredit("123", "tv"), new TmdbCredit("124", "movie")))));
        lenient().when(trackedPersonCreditRepository.findByTrackedPersonStateId(any())).thenReturn(List.of());

        followedPersonTrackingService.trackFollowedPeopleCredits();

        verify(trackedPersonCreditRepository, times(3)).save(trackedPersonCreditCaptor.capture());
        assertThat(trackedPersonCreditCaptor.getAllValues())
                .extracting(credit -> credit.getCreditType().name() + "/" + credit.getCreditTmdbId())
                .containsExactlyInAnyOrder("MOVIE/123", "SERIES/123", "MOVIE/124");
        verify(trackedPersonCreditRepository).findByTrackedPersonStateId(any());
        verify(contentService, never()).getOrCreateReference(any());
        verify(followedPersonRepository, never()).findUserIdsByPersonTmdbId(any());
        verify(contentRepository, never()).getReferenceById(any());
        verify(notificationRepository, never()).saveAll(any());
        InOrder ordering = inOrder(tmdbClient, advisoryLock);
        ordering.verify(tmdbClient).getPersonCombinedCredits(PERSON_TMDB_ID);
        ordering.verify(advisoryLock).lock("followed-person-tracking:" + PERSON_TMDB_ID);
    }

    @Test
    @DisplayName("[trackFollowedPeopleCredits] Should Ignore Unsupported Media Types - When A Credit Is Not Movie Or TV")
    void shouldIgnoreUnsupportedMediaTypesWhenACreditIsNotMovieOrTv() {
        when(followedPersonRepository.findDistinctPersonTmdbIds()).thenReturn(List.of(PERSON_TMDB_ID));
        TrackedPersonState state = state(PERSON_TMDB_ID);
        when(trackedPersonStateRepository.findByPersonTmdbId(PERSON_TMDB_ID)).thenReturn(Optional.of(state));
        when(tmdbClient.getPersonCombinedCredits(PERSON_TMDB_ID)).thenReturn(Optional.of(
                new TmdbPersonCredits(List.of(new TmdbCredit("777", "person")), List.of())));
        lenient().when(trackedPersonCreditRepository.findByTrackedPersonStateId(state.getId())).thenReturn(List.of());

        followedPersonTrackingService.trackFollowedPeopleCredits();

        verify(contentService, never()).getOrCreateReference(any());
        verify(trackedPersonCreditRepository, never()).save(any());
        verify(notificationRepository, never()).saveAll(any());
    }

    @Test
    @DisplayName("[trackFollowedPeopleCredits] Should Store One Credit And Notify Once - When Cast And Crew Repeat The Same Typed Credit")
    void shouldStoreOneCreditAndNotifyOnceWhenCastAndCrewRepeatTheSameTypedCredit() {
        when(followedPersonRepository.findDistinctPersonTmdbIds()).thenReturn(List.of(PERSON_TMDB_ID));
        TrackedPersonState state = state(PERSON_TMDB_ID);
        when(trackedPersonStateRepository.findByPersonTmdbId(PERSON_TMDB_ID)).thenReturn(Optional.of(state));
        TmdbCredit duplicate = new TmdbCredit("605", "movie");
        when(tmdbClient.getPersonCombinedCredits(PERSON_TMDB_ID)).thenReturn(Optional.of(
                new TmdbPersonCredits(List.of(duplicate), List.of(duplicate))));
        lenient().when(trackedPersonCreditRepository.findByTrackedPersonStateId(state.getId())).thenReturn(List.of());
        ContentRefDTO contentRef = contentRef("605", ContentType.MOVIE);
        when(contentService.getOrCreateReference(any())).thenReturn(contentRef);
        when(contentRepository.getReferenceById(contentRef.id())).thenReturn(content(contentRef));
        when(followedPersonRepository.findUserIdsByPersonTmdbId(PERSON_TMDB_ID)).thenReturn(List.of(followerId));

        followedPersonTrackingService.trackFollowedPeopleCredits();

        verify(contentService, times(1)).getOrCreateReference(any());
        verify(trackedPersonCreditRepository, times(1)).save(any(TrackedPersonCredit.class));
        verify(notificationRepository, times(1)).saveAll(any());
    }

    @Test
    @DisplayName("[trackFollowedPeopleCredits] Should Keep A Baseline Rerun Idempotent - When Repairing Previously Stored Typed IDs")
    void shouldKeepABaselineRerunIdempotentWhenRepairingPreviouslyStoredTypedIds() {
        when(followedPersonRepository.findDistinctPersonTmdbIds()).thenReturn(List.of(PERSON_TMDB_ID));
        AtomicReference<TrackedPersonState> currentState = new AtomicReference<>();
        when(trackedPersonStateRepository.findByPersonTmdbId(PERSON_TMDB_ID))
                .thenAnswer(invocation -> Optional.ofNullable(currentState.get()));
        when(tmdbClient.getPersonCombinedCredits(PERSON_TMDB_ID)).thenReturn(Optional.of(new TmdbPersonCredits(
                List.of(new TmdbCredit("123", "movie")), List.of(new TmdbCredit("123", "tv")))));
        CopyOnWriteArrayList<TrackedPersonCredit> storedCredits = new CopyOnWriteArrayList<>();
        when(trackedPersonStateRepository.save(any(TrackedPersonState.class)))
                .thenAnswer(invocation -> {
                    TrackedPersonState saved = invocation.getArgument(0);
                    if (saved.getId() == null) saved.setId(UUID.randomUUID());
                    currentState.set(saved);
                    return saved;
                });
        when(trackedPersonCreditRepository.findByTrackedPersonStateId(any()))
                .thenAnswer(invocation -> List.copyOf(storedCredits));
        when(trackedPersonCreditRepository.save(any(TrackedPersonCredit.class)))
                .thenAnswer(invocation -> {
                    TrackedPersonCredit saved = invocation.getArgument(0);
                    storedCredits.add(saved);
                    return saved;
                });

        followedPersonTrackingService.trackFollowedPeopleCredits();
        followedPersonTrackingService.trackFollowedPeopleCredits();

        assertThat(storedCredits).hasSize(2);
        verify(contentService, never()).getOrCreateReference(any());
        verify(trackedPersonCreditRepository, times(2)).save(any(TrackedPersonCredit.class));
        verify(notificationRepository, never()).saveAll(any());
    }

    @Test
    @DisplayName("[trackFollowedPeopleCredits] Should Notify Once For A Genuinely New Credit - When Older Credits Are Also Present")
    void shouldNotifyOnceForAGenuinelyNewCreditWhenOlderCreditsAreAlsoPresent() {
        when(followedPersonRepository.findDistinctPersonTmdbIds()).thenReturn(List.of(PERSON_TMDB_ID));
        TrackedPersonState state = state(PERSON_TMDB_ID);
        when(trackedPersonStateRepository.findByPersonTmdbId(PERSON_TMDB_ID)).thenReturn(Optional.of(state));
        when(tmdbClient.getPersonCombinedCredits(PERSON_TMDB_ID)).thenReturn(Optional.of(new TmdbPersonCredits(
                List.of(new TmdbCredit("603", "movie"), new TmdbCredit("605", "movie")), List.of())));
        lenient().when(trackedPersonCreditRepository.findByTrackedPersonStateId(state.getId()))
                .thenReturn(List.of(trackedCredit(state, "603", ContentType.MOVIE)));
        ContentRefDTO newCreditRef = contentRef("605", ContentType.MOVIE);
        when(contentService.getOrCreateReference(any())).thenReturn(newCreditRef);
        Content newCreditContent = content(newCreditRef);
        when(contentRepository.getReferenceById(newCreditRef.id())).thenReturn(newCreditContent);
        when(followedPersonRepository.findUserIdsByPersonTmdbId(PERSON_TMDB_ID)).thenReturn(List.of(followerId));

        followedPersonTrackingService.trackFollowedPeopleCredits();

        verify(contentService, times(1)).getOrCreateReference(any());
        verify(notificationRepository, times(1)).saveAll(notificationsCaptor.capture());
        assertThat(notificationsCaptor.getValue()).hasSize(1);
        assertThat(notificationsCaptor.getValue().get(0).getContent()).isEqualTo(newCreditContent);
        verify(trackedPersonCreditRepository, times(1)).save(any(TrackedPersonCredit.class));
    }

    @Test
    @DisplayName("[trackFollowedPeopleCredits] Should Not Duplicate The New Credit Or Notification - When Two Jobs Process One Person")
    void shouldNotDuplicateTheNewCreditOrNotificationWhenTwoJobsProcessOnePerson() throws Exception {
        when(followedPersonRepository.findDistinctPersonTmdbIds()).thenReturn(List.of(PERSON_TMDB_ID));
        TrackedPersonState state = state(PERSON_TMDB_ID);
        when(trackedPersonStateRepository.findByPersonTmdbId(PERSON_TMDB_ID)).thenReturn(Optional.of(state));
        when(tmdbClient.getPersonCombinedCredits(PERSON_TMDB_ID)).thenReturn(Optional.of(
                new TmdbPersonCredits(List.of(new TmdbCredit("605", "movie")), List.of())));

        CopyOnWriteArrayList<TrackedPersonCredit> storedCredits = new CopyOnWriteArrayList<>();
        CountDownLatch bothPreflightReads = new CountDownLatch(2);
        java.util.concurrent.atomic.AtomicInteger keyReadCount = new java.util.concurrent.atomic.AtomicInteger();
        lenient().when(trackedPersonCreditRepository.findByTrackedPersonStateId(state.getId())).thenAnswer(invocation -> {
            int read = keyReadCount.incrementAndGet();
            if (read <= 2) {
                bothPreflightReads.countDown();
                assertThat(bothPreflightReads.await(5, TimeUnit.SECONDS)).isTrue();
            }
            return List.copyOf(storedCredits);
        });
        when(trackedPersonCreditRepository.save(any(TrackedPersonCredit.class)))
                .thenAnswer(invocation -> {
                    TrackedPersonCredit saved = invocation.getArgument(0);
                    storedCredits.add(saved);
                    return saved;
                });

        ReentrantLock personLock = new ReentrantLock();
        doAnswer(invocation -> {
            personLock.lock();
            return null;
        }).when(advisoryLock).lock("followed-person-tracking:" + PERSON_TMDB_ID);
        doAnswer(invocation -> {
            try {
                return ((Supplier<?>) invocation.getArgument(0)).get();
            } finally {
                if (personLock.isHeldByCurrentThread()) personLock.unlock();
            }
        }).when(newTransactionExecutor).runInNewTransaction(any());

        ContentRefDTO contentRef = contentRef("605", ContentType.MOVIE);
        when(contentService.getOrCreateReference(any())).thenReturn(contentRef);
        when(contentRepository.getReferenceById(contentRef.id())).thenReturn(content(contentRef));
        when(followedPersonRepository.findUserIdsByPersonTmdbId(PERSON_TMDB_ID)).thenReturn(List.of(followerId));

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            var firstJob = executor.submit(followedPersonTrackingService::trackFollowedPeopleCredits);
            var secondJob = executor.submit(followedPersonTrackingService::trackFollowedPeopleCredits);
            firstJob.get(10, TimeUnit.SECONDS);
            secondJob.get(10, TimeUnit.SECONDS);
        }

        assertThat(storedCredits).hasSize(1);
        verify(trackedPersonCreditRepository, times(1)).save(any(TrackedPersonCredit.class));
        verify(notificationRepository, times(1)).saveAll(any());
    }

    @Test
    @DisplayName("[trackFollowedPeopleCredits] Should Keep A Concurrent First Baseline Silent - When Two Jobs Start Without State")
    void shouldKeepAConcurrentFirstBaselineSilentWhenTwoJobsStartWithoutState() throws Exception {
        when(followedPersonRepository.findDistinctPersonTmdbIds()).thenReturn(List.of(PERSON_TMDB_ID));
        when(tmdbClient.getPersonCombinedCredits(PERSON_TMDB_ID)).thenReturn(Optional.of(
                new TmdbPersonCredits(List.of(new TmdbCredit("603", "movie")), List.of())));

        AtomicReference<TrackedPersonState> currentState = new AtomicReference<>();
        CountDownLatch bothInitialSnapshots = new CountDownLatch(2);
        java.util.concurrent.atomic.AtomicInteger stateReadCount = new java.util.concurrent.atomic.AtomicInteger();
        when(trackedPersonStateRepository.findByPersonTmdbId(PERSON_TMDB_ID)).thenAnswer(invocation -> {
            if (stateReadCount.incrementAndGet() <= 2) {
                bothInitialSnapshots.countDown();
                assertThat(bothInitialSnapshots.await(5, TimeUnit.SECONDS)).isTrue();
                return Optional.empty();
            }
            return Optional.ofNullable(currentState.get());
        });
        when(trackedPersonStateRepository.save(any(TrackedPersonState.class)))
                .thenAnswer(invocation -> {
                    TrackedPersonState saved = invocation.getArgument(0);
                    if (saved.getId() == null) saved.setId(UUID.randomUUID());
                    currentState.set(saved);
                    return saved;
                });

        CopyOnWriteArrayList<TrackedPersonCredit> storedCredits = new CopyOnWriteArrayList<>();
        lenient().when(trackedPersonCreditRepository.findByTrackedPersonStateId(any()))
                .thenAnswer(invocation -> List.copyOf(storedCredits));
        when(trackedPersonCreditRepository.save(any(TrackedPersonCredit.class)))
                .thenAnswer(invocation -> {
                    TrackedPersonCredit saved = invocation.getArgument(0);
                    storedCredits.add(saved);
                    return saved;
                });

        ReentrantLock personLock = new ReentrantLock();
        doAnswer(invocation -> {
            personLock.lock();
            return null;
        }).when(advisoryLock).lock("followed-person-tracking:" + PERSON_TMDB_ID);
        doAnswer(invocation -> {
            try {
                return ((Supplier<?>) invocation.getArgument(0)).get();
            } finally {
                if (personLock.isHeldByCurrentThread()) personLock.unlock();
            }
        }).when(newTransactionExecutor).runInNewTransaction(any());

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            var firstJob = executor.submit(followedPersonTrackingService::trackFollowedPeopleCredits);
            var secondJob = executor.submit(followedPersonTrackingService::trackFollowedPeopleCredits);
            firstJob.get(10, TimeUnit.SECONDS);
            secondJob.get(10, TimeUnit.SECONDS);
        }

        assertThat(currentState.get()).isNotNull();
        assertThat(storedCredits).hasSize(1);
        assertThat(stateReadCount.get()).isEqualTo(6);
        verify(contentService, never()).getOrCreateReference(any());
        verify(trackedPersonCreditRepository, times(1)).save(any(TrackedPersonCredit.class));
        verify(notificationRepository, never()).saveAll(any());
    }

    private TrackedPersonState state(String personTmdbId) {
        return TrackedPersonState.builder()
                .id(UUID.randomUUID())
                .personTmdbId(personTmdbId)
                .lastCheckedAt(LocalDateTime.now())
                .build();
    }

    private TrackedPersonCredit trackedCredit(TrackedPersonState state, String tmdbId, ContentType type) {
        return TrackedPersonCredit.builder()
                .trackedPersonState(state)
                .creditTmdbId(tmdbId)
                .creditType(type)
                .build();
    }

    private ContentRefDTO contentRef(String tmdbId, ContentType type) {
        return new ContentRefDTO(UUID.randomUUID(), tmdbId, type, null, null, null, null, null, null, null);
    }

    private Content content(ContentRefDTO contentRef) {
        return Content.builder()
                .id(contentRef.id())
                .tmdbId(contentRef.tmdbId())
                .type(contentRef.type())
                .build();
    }
}
