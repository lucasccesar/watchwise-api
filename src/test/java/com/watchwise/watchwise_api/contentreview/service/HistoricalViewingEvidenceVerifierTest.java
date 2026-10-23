package com.watchwise.watchwise_api.contentreview.service;

import com.watchwise.watchwise_api.calendar.repository.CalendarScheduleCompletenessRepository;
import com.watchwise.watchwise_api.common.exception.BadRequestException;
import com.watchwise.watchwise_api.common.exception.ConflictException;
import com.watchwise.watchwise_api.content.dto.MediaReference;
import com.watchwise.watchwise_api.content.entity.Content;
import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.diarycompletion.entity.UserSeriesHistoryState;
import com.watchwise.watchwise_api.diarycompletion.model.CompletionScope;
import com.watchwise.watchwise_api.diarycompletion.model.EpisodeCoordinate;
import com.watchwise.watchwise_api.diarycompletion.model.VersionedCompletionScope;
import com.watchwise.watchwise_api.diarycompletion.repository.UserSeriesHistoryStateRepository;
import com.watchwise.watchwise_api.diarycompletion.service.CompletionScopeLoader;
import com.watchwise.watchwise_api.diaryentry.repository.DiaryEntryRepository;
import com.watchwise.watchwise_api.user.entity.User;
import com.watchwise.watchwise_api.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class HistoricalViewingEvidenceVerifierTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private com.watchwise.watchwise_api.content.repository.ContentRepository contentRepository;

    @Mock
    private DiaryEntryRepository diaryEntryRepository;

    @Mock
    private UserSeriesHistoryStateRepository historyStateRepository;

    @Mock
    private CalendarScheduleCompletenessRepository completenessRepository;

    @Mock
    private CompletionScopeLoader scopeLoader;

    @Mock
    private Clock clock;

    @InjectMocks
    private HistoricalViewingEvidenceVerifier verifier;

    @Test
    void acceptsImmutableEventIdSetsWithoutNullMembershipProbe() {
        UUID eventId = UUID.randomUUID();

        HistoricalViewingEvidence evidence = new HistoricalViewingEvidence(
                MediaReference.series("tv-1"), 1, "scope-hash", Set.of(eventId));

        assertThat(evidence.eventIds()).containsExactly(eventId);
    }

    @Test
    void rejectsEventIdsThatAreNotPersistedForTheOwner() {
        UUID ownerId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        MediaReference reference = MediaReference.series("tv-1");
        Content content = series("tv-1");
        HistoricalViewingEvidence evidence = new HistoricalViewingEvidence(
                reference, 2, "scope-hash", Set.of(eventId));
        when(userRepository.findById(ownerId)).thenReturn(Optional.of(user(ownerId)));
        when(scopeLoader.load(eq(reference), eq("US"), eq("en-US"), any(Instant.class)))
                .thenReturn(scope(reference));
        when(diaryEntryRepository.findEpisodeCompletionWatchesByUserIdAndDiaryEntryIdIn(ownerId, Set.of(eventId)))
                .thenReturn(List.of());

        assertThatThrownBy(() -> verifier.verify(ownerId, content, evidence, true, Instant.now()))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void onlyProvesCompletionWhenPersistedOwnerPassEventsExactlyCoverTheScope() {
        UUID ownerId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        MediaReference reference = MediaReference.series("tv-1");
        Content content = series("tv-1");
        HistoricalViewingEvidence evidence = new HistoricalViewingEvidence(
                reference, 2, "scope-hash", Set.of(eventId));
        DiaryEntryRepository.EpisodeCompletionWatch event = mock(DiaryEntryRepository.EpisodeCompletionWatch.class);
        when(event.getSeriesTmdbId()).thenReturn("tv-1");
        when(event.getSeasonNumber()).thenReturn(1);
        when(event.getEpisodeNumber()).thenReturn(1);
        when(event.getWatchNumber()).thenReturn(2);
        when(userRepository.findById(ownerId)).thenReturn(Optional.of(user(ownerId)));
        when(scopeLoader.load(eq(reference), eq("US"), eq("en-US"), any(Instant.class)))
                .thenReturn(scope(reference));
        when(diaryEntryRepository.findEpisodeCompletionWatchesByUserIdAndDiaryEntryIdIn(ownerId, Set.of(eventId)))
                .thenReturn(List.of(event));

        HistoricalViewingEvidenceVerifier.Verification result =
                verifier.verify(ownerId, content, evidence, true, Instant.now());

        assertThat(result.completionScopeProven()).isTrue();
        assertThat(result.eventIds()).containsExactly(eventId);
    }

    @Test
    void rejectsExpiredScopeWhenCompleteProofIsRequired() {
        UUID ownerId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        MediaReference reference = MediaReference.series("tv-1");
        Content content = series("tv-1");
        HistoricalViewingEvidence evidence = new HistoricalViewingEvidence(
                reference, 2, "scope-hash", Set.of(eventId));
        Instant now = Instant.now();
        stubCompletePass(ownerId, eventId, reference, now.minusSeconds(1));

        assertThatThrownBy(() -> verifier.verify(ownerId, content, evidence, true, now))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void expiredScopeIsUnprovenWhenCompletionIsOptional() {
        UUID ownerId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        MediaReference reference = MediaReference.series("tv-1");
        Content content = series("tv-1");
        HistoricalViewingEvidence evidence = new HistoricalViewingEvidence(
                reference, 2, "scope-hash", Set.of(eventId));
        Instant now = Instant.now();
        Instant validUntil = now.minusSeconds(1);
        stubCompletePass(ownerId, eventId, reference, validUntil);

        HistoricalViewingEvidenceVerifier.Verification verification =
                verifier.verify(ownerId, content, evidence, false, now);

        assertThat(verification.completionScopeProven()).isFalse();
        assertThat(verification.validUntil()).isEqualTo(validUntil);
    }

    @Test
    void revalidationChecksInjectedClockAfterLoadingPersistedEvidence() {
        UUID ownerId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        MediaReference reference = MediaReference.series("tv-1");
        Content content = series("tv-1");
        HistoricalViewingEvidence evidence = new HistoricalViewingEvidence(
                reference, 2, "scope-hash", Set.of(eventId));
        DiaryEntryRepository.EpisodeCompletionWatch event = mock(DiaryEntryRepository.EpisodeCompletionWatch.class);
        when(event.getSeriesTmdbId()).thenReturn("tv-1");
        when(event.getSeasonNumber()).thenReturn(1);
        when(event.getEpisodeNumber()).thenReturn(1);
        when(event.getWatchNumber()).thenReturn(2);
        when(userRepository.findById(ownerId)).thenReturn(Optional.of(user(ownerId)));
        when(diaryEntryRepository.findEpisodeCompletionWatchesByUserIdAndDiaryEntryIdIn(ownerId, Set.of(eventId)))
                .thenReturn(List.of(event));
        Instant validUntil = Instant.parse("2030-01-01T00:00:00Z");
        when(clock.instant()).thenReturn(validUntil.plusNanos(1));
        HistoricalViewingEvidenceVerifier.Verification verification =
                new HistoricalViewingEvidenceVerifier.Verification(0L, 0L, validUntil, true, Set.of(eventId));

        assertThatThrownBy(() -> verifier.revalidate(ownerId, content, evidence, verification))
                .isInstanceOf(ConflictException.class);

        var order = inOrder(historyStateRepository, diaryEntryRepository, userRepository, completenessRepository, clock);
        order.verify(historyStateRepository).findById(new UserSeriesHistoryState.Key(ownerId, "tv-1"));
        order.verify(diaryEntryRepository)
                .findEpisodeCompletionWatchesByUserIdAndDiaryEntryIdIn(ownerId, Set.of(eventId));
        order.verify(userRepository).findById(ownerId);
        order.verify(completenessRepository).findByGroupTypeAndSeriesTmdbIdAndSeasonNumberAndRegionAndLanguage(
                        com.watchwise.watchwise_api.calendar.entity.CalendarScheduleCompleteness.GroupType.SERIES,
                        "tv-1", null, "US", "en-US");
        order.verify(clock).instant();
    }

    @Test
    void rejectsHistoricalEvidenceWhenDiaryRevisionChangesBeforeSave() {
        UUID ownerId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        MediaReference reference = MediaReference.series("tv-1");
        Content content = series("tv-1");
        HistoricalViewingEvidence evidence = new HistoricalViewingEvidence(
                reference, 2, "scope-hash", Set.of(eventId));
        HistoricalViewingEvidenceVerifier.Verification captured =
                new HistoricalViewingEvidenceVerifier.Verification(
                        1L, 0L, Instant.now().plusSeconds(3600), true, Set.of(eventId));
        when(historyStateRepository.findById(new UserSeriesHistoryState.Key(ownerId, "tv-1")))
                .thenReturn(Optional.of(UserSeriesHistoryState.builder()
                        .id(new UserSeriesHistoryState.Key(ownerId, "tv-1"))
                        .historyRevision(2L)
                        .computedRevision(2L)
                        .scheduleVersion(0L)
                        .build()));

        assertThatThrownBy(() -> verifier.revalidate(ownerId, content, evidence, captured))
                .isInstanceOf(ConflictException.class);
    }

    private User user(UUID id) {
        return User.builder().id(id).preferredRegion("US").preferredLanguage("en-US").build();
    }

    private Content series(String tmdbId) {
        return Content.builder().id(UUID.randomUUID()).tmdbId(tmdbId).type(ContentType.SERIES).build();
    }

    private VersionedCompletionScope scope(MediaReference reference) {
        return scope(reference, Instant.now().plusSeconds(3600));
    }

    private void stubCompletePass(UUID ownerId, UUID eventId, MediaReference reference, Instant validUntil) {
        DiaryEntryRepository.EpisodeCompletionWatch event = mock(DiaryEntryRepository.EpisodeCompletionWatch.class);
        when(event.getSeriesTmdbId()).thenReturn("tv-1");
        when(event.getSeasonNumber()).thenReturn(1);
        when(event.getEpisodeNumber()).thenReturn(1);
        when(event.getWatchNumber()).thenReturn(2);
        when(userRepository.findById(ownerId)).thenReturn(Optional.of(user(ownerId)));
        when(scopeLoader.load(eq(reference), eq("US"), eq("en-US"), any(Instant.class)))
                .thenReturn(scope(reference, validUntil));
        when(diaryEntryRepository.findEpisodeCompletionWatchesByUserIdAndDiaryEntryIdIn(ownerId, Set.of(eventId)))
                .thenReturn(List.of(event));
    }

    private VersionedCompletionScope scope(MediaReference reference, Instant validUntil) {
        return new VersionedCompletionScope(
                new CompletionScope(reference, Set.of(new EpisodeCoordinate(1, 1)), true),
                "scope-hash",
                validUntil);
    }
}
