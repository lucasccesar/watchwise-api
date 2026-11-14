package com.watchwise.watchwise_api.contentrating.service.impl;

import com.watchwise.watchwise_api.contentrating.service.OpinionEligibility;
import com.watchwise.watchwise_api.contentrating.service.OpinionEvidenceService;
import com.watchwise.watchwise_api.contentrating.entity.ContentRating;
import com.watchwise.watchwise_api.contentrating.entity.ContentRatingPass;
import com.watchwise.watchwise_api.contentrating.repository.ContentRatingRepository;
import com.watchwise.watchwise_api.contentrating.repository.ContentRatingPassRepository;
import com.watchwise.watchwise_api.common.exception.BadRequestException;
import com.watchwise.watchwise_api.content.entity.Content;
import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.content.repository.ContentRepository;
import com.watchwise.watchwise_api.contentreview.service.HistoricalViewingEvidenceVerifier;
import com.watchwise.watchwise_api.contentreview.service.HistoricalViewingEvidence;
import com.watchwise.watchwise_api.content.dto.MediaReference;
import com.watchwise.watchwise_api.common.exception.ConflictException;
import com.watchwise.watchwise_api.common.transaction.UserContentMutationLock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.ArgumentMatchers;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ContentRatingServiceTest {

    @Mock
    private ContentRatingRepository ratingRepository;

    @Mock
    private ContentRatingPassRepository passRatingRepository;

    @Mock
    private ContentRepository contentRepository;

    @Mock
    private OpinionEvidenceService opinionEvidenceService;

    @Mock
    private HistoricalViewingEvidenceVerifier historicalEvidenceVerifier;

    @Mock
    private UserContentMutationLock userContentMutationLock;

    @InjectMocks
    private ContentRatingServiceImpl ratingService;

    @Test
    void ratingEligibilityRequiresCurrentCompletionOrPreviouslyEligibleRatingWithRemainingEvidence() {
        assertThat(ContentRatingServiceImpl.canSetRating(
                false, new OpinionEligibility(false, true, false))).isFalse();
        assertThat(ContentRatingServiceImpl.canSetRating(
                false, new OpinionEligibility(true, false, false))).isFalse();
        assertThat(ContentRatingServiceImpl.canSetRating(
                true, new OpinionEligibility(true, false, true))).isTrue();
        assertThat(ContentRatingServiceImpl.canSetRating(
                false, new OpinionEligibility(true, true, false))).isTrue();
    }

    @Test
    void setStoresScoreOnVerifiedCompletedPass() {
        UUID ownerId = UUID.randomUUID();
        UUID contentId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        Instant now = Instant.parse("2026-11-14T12:00:00Z");
        Content content = Content.builder()
                .id(contentId)
                .tmdbId("tv-pass-one")
                .type(ContentType.SERIES)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
        OpinionEvidenceService.Snapshot evidence = new OpinionEvidenceService.Snapshot(
                new OpinionEligibility(true, true, false),
                1L,
                1L,
                1,
                Set.of(eventId),
                Set.of(eventId),
                true);
        when(contentRepository.findById(contentId)).thenReturn(Optional.of(content));
        when(ratingRepository.findByUserIdAndContentId(ownerId, contentId)).thenReturn(Optional.empty());
        when(opinionEvidenceService.capture(eq(ownerId), eq(content), eq(true), any(Instant.class)))
                .thenReturn(evidence);
        when(ratingRepository.saveAndFlush(any(ContentRating.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(passRatingRepository.findByUserIdAndContentIdAndWatchNumber(ownerId, contentId, 1))
                .thenReturn(Optional.empty());
        when(passRatingRepository.saveAndFlush(any(ContentRatingPass.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        ratingService.set(ownerId, contentId, 9, null);

        verify(passRatingRepository).saveAndFlush(ArgumentMatchers.argThat(pass ->
                pass.getUserId().equals(ownerId)
                        && pass.getContentId().equals(contentId)
                        && pass.getWatchNumber() == 1
                        && pass.getScore() == 9));
        verify(ratingRepository).saveAndFlush(ArgumentMatchers.argThat(rating ->
                rating.getWatchNumber() == 1 && rating.getScore() == 9));
    }

    @Test
    void setRejectsNewRatingWhenCompletedPassNumberIsMissing() {
        UUID ownerId = UUID.randomUUID();
        UUID contentId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        Content content = Content.builder()
                .id(contentId)
                .tmdbId("tv-missing-pass-number")
                .type(ContentType.SERIES)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
        OpinionEvidenceService.Snapshot evidence = new OpinionEvidenceService.Snapshot(
                new OpinionEligibility(true, true, false),
                1L,
                1L,
                null,
                Set.of(eventId),
                Set.of(eventId),
                true);
        when(contentRepository.findById(contentId)).thenReturn(Optional.of(content));
        when(ratingRepository.findByUserIdAndContentId(ownerId, contentId)).thenReturn(Optional.empty());
        when(opinionEvidenceService.capture(eq(ownerId), eq(content), eq(true), any(Instant.class)))
                .thenReturn(evidence);

        assertThatThrownBy(() -> ratingService.set(ownerId, contentId, 9, null))
                .isInstanceOf(BadRequestException.class);

        verify(passRatingRepository, never()).saveAndFlush(any(ContentRatingPass.class));
        verify(ratingRepository, never()).saveAndFlush(any(ContentRating.class));
    }

    @Test
    void setUpdatesTheSamePassWithoutAddingHistory() {
        UUID ownerId = UUID.randomUUID();
        UUID contentId = UUID.randomUUID();
        UUID passId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        Instant now = Instant.parse("2026-11-14T12:00:00Z");
        Content content = Content.builder()
                .id(contentId)
                .tmdbId("tv-pass-one")
                .type(ContentType.SERIES)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
        ContentRating current = ContentRating.builder()
                .id(UUID.randomUUID())
                .userId(ownerId)
                .contentId(contentId)
                .contentType(ContentType.SERIES)
                .score(9)
                .watchNumber(1)
                .version(2L)
                .createdAt(now)
                .updatedAt(now)
                .build();
        ContentRatingPass pass = ContentRatingPass.builder()
                .id(passId)
                .userId(ownerId)
                .contentId(contentId)
                .contentType(ContentType.SERIES)
                .watchNumber(1)
                .score(9)
                .createdAt(now)
                .updatedAt(now)
                .build();
        OpinionEvidenceService.Snapshot evidence = new OpinionEvidenceService.Snapshot(
                new OpinionEligibility(true, false, true),
                1L,
                1L,
                null,
                Set.of(eventId),
                Set.of(),
                true);
        when(contentRepository.findById(contentId)).thenReturn(Optional.of(content));
        when(ratingRepository.findByUserIdAndContentId(ownerId, contentId)).thenReturn(Optional.of(current));
        when(opinionEvidenceService.capture(eq(ownerId), eq(content), eq(true), any(Instant.class)))
                .thenReturn(evidence);
        when(passRatingRepository.findByUserIdAndContentIdAndWatchNumber(ownerId, contentId, 1))
                .thenReturn(Optional.of(pass));
        when(ratingRepository.saveAndFlush(any(ContentRating.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        var response = ratingService.set(ownerId, contentId, 10, 2L);

        ArgumentCaptor<ContentRatingPass> passCaptor = ArgumentCaptor.forClass(ContentRatingPass.class);
        verify(passRatingRepository).saveAndFlush(passCaptor.capture());
        assertThat(passCaptor.getValue().getId()).isEqualTo(passId);
        assertThat(passCaptor.getValue().getScore()).isEqualTo(10);
        assertThat(response.score()).isEqualTo(10);
    }

    @Test
    void setStoresNewPassWhenExistingRatingAndNewCompletionIsCurrent() {
        UUID ownerId = UUID.randomUUID();
        UUID contentId = UUID.randomUUID();
        UUID passOneId = UUID.randomUUID();
        UUID eventOneId = UUID.randomUUID();
        UUID eventTwoId = UUID.randomUUID();
        Instant now = Instant.parse("2026-11-14T12:00:00Z");
        Content content = Content.builder()
                .id(contentId)
                .tmdbId("tv-pass-two")
                .type(ContentType.SERIES)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
        ContentRating current = ContentRating.builder()
                .id(UUID.randomUUID())
                .userId(ownerId)
                .contentId(contentId)
                .contentType(ContentType.SERIES)
                .score(9)
                .watchNumber(1)
                .version(2L)
                .createdAt(now)
                .updatedAt(now)
                .build();
        ContentRatingPass passOne = ContentRatingPass.builder()
                .id(passOneId)
                .userId(ownerId)
                .contentId(contentId)
                .contentType(ContentType.SERIES)
                .watchNumber(1)
                .score(9)
                .createdAt(now)
                .updatedAt(now)
                .build();
        Map<Integer, ContentRatingPass> history = new HashMap<>(Map.of(1, passOne));
        OpinionEvidenceService.Snapshot incompletePass = new OpinionEvidenceService.Snapshot(
                new OpinionEligibility(true, false, false),
                2L,
                3L,
                null,
                Set.of(eventOneId),
                Set.of(),
                false);
        OpinionEvidenceService.Snapshot completedPass = new OpinionEvidenceService.Snapshot(
                new OpinionEligibility(true, true, false),
                2L,
                3L,
                2,
                Set.of(eventOneId, eventTwoId),
                Set.of(eventTwoId),
                true);
        when(contentRepository.findById(contentId)).thenReturn(Optional.of(content));
        when(ratingRepository.findByUserIdAndContentId(ownerId, contentId)).thenReturn(Optional.of(current));
        when(opinionEvidenceService.capture(eq(ownerId), eq(content), ArgumentMatchers.anyBoolean(), any(Instant.class)))
                .thenAnswer(invocation -> Boolean.TRUE.equals(invocation.getArgument(2))
                        ? completedPass
                        : incompletePass);
        when(passRatingRepository.findByUserIdAndContentIdAndWatchNumber(
                eq(ownerId), eq(contentId), ArgumentMatchers.anyInt()))
                .thenAnswer(invocation -> Optional.ofNullable(history.get(invocation.getArgument(2))));
        when(passRatingRepository.saveAndFlush(any(ContentRatingPass.class)))
                .thenAnswer(invocation -> {
                    ContentRatingPass saved = invocation.getArgument(0);
                    history.put(saved.getWatchNumber(), saved);
                    return saved;
                });
        when(ratingRepository.saveAndFlush(any(ContentRating.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        ratingService.set(ownerId, contentId, 10, 2L);

        assertThat(history.get(1).getScore()).isEqualTo(9);
        assertThat(history.get(2).getScore()).isEqualTo(10);
        assertThat(current.getWatchNumber()).isEqualTo(2);
        assertThat(current.getScore()).isEqualTo(10);
    }

    @Test
    void removeCurrentPassRatingFallsBackToThePreviousPass() {
        UUID ownerId = UUID.randomUUID();
        UUID contentId = UUID.randomUUID();
        Instant now = Instant.parse("2026-11-14T12:00:00Z");
        Content content = Content.builder()
                .id(contentId)
                .tmdbId("tv-pass-delete")
                .type(ContentType.SERIES)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
        ContentRating current = ContentRating.builder()
                .id(UUID.randomUUID())
                .userId(ownerId)
                .contentId(contentId)
                .contentType(ContentType.SERIES)
                .score(10)
                .watchNumber(2)
                .version(4L)
                .createdAt(now)
                .updatedAt(now)
                .build();
        ContentRatingPass currentPass = pass(ownerId, contentId, 2, 10, now);
        ContentRatingPass previousPass = pass(ownerId, contentId, 1, 9, now);
        when(contentRepository.findById(contentId)).thenReturn(Optional.of(content));
        when(ratingRepository.findByUserIdAndContentId(ownerId, contentId)).thenReturn(Optional.of(current));
        when(passRatingRepository.findByUserIdAndContentIdAndWatchNumber(ownerId, contentId, 2))
                .thenReturn(Optional.of(currentPass));
        when(passRatingRepository.findFirstByUserIdAndContentIdAndWatchNumberLessThanOrderByWatchNumberDesc(
                ownerId, contentId, 2)).thenReturn(Optional.of(previousPass));
        when(ratingRepository.saveAndFlush(any(ContentRating.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        ratingService.remove(ownerId, contentId);

        verify(passRatingRepository).delete(currentPass);
        verify(passRatingRepository, never()).delete(previousPass);
        verify(ratingRepository).saveAndFlush(ArgumentMatchers.argThat(rating ->
                rating.getScore() == 9 && rating.getWatchNumber() == 1));
    }

    @Test
    void restoreInitialAddsOlderPassWithoutReplacingNewerProjection() {
        UUID ownerId = UUID.randomUUID();
        UUID contentId = UUID.randomUUID();
        UUID currentRatingId = UUID.randomUUID();
        UUID restoredPassRatingId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        Instant now = Instant.parse("2026-11-14T12:00:00Z");
        MediaReference reference = MediaReference.series("tv-pass-restore");
        HistoricalViewingEvidence evidence = new HistoricalViewingEvidence(
                reference, 1, "scope-hash", Set.of(eventId));
        Content content = Content.builder()
                .id(contentId)
                .tmdbId("tv-pass-restore")
                .type(ContentType.SERIES)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
        ContentRating current = ContentRating.builder()
                .id(currentRatingId)
                .userId(ownerId)
                .contentId(contentId)
                .contentType(ContentType.SERIES)
                .score(10)
                .watchNumber(2)
                .version(3L)
                .createdAt(now)
                .updatedAt(now)
                .build();
        HistoricalViewingEvidenceVerifier.Verification verification =
                new HistoricalViewingEvidenceVerifier.Verification(
                        4L, 5L, now.plusSeconds(3600), true, Set.of(eventId));
        when(contentRepository.findByTmdbIdAndType("tv-pass-restore", ContentType.SERIES))
                .thenReturn(Optional.of(content));
        when(historicalEvidenceVerifier.verify(
                eq(ownerId), eq(content), eq(evidence), eq(true), any(Instant.class)))
                .thenReturn(verification);
        when(passRatingRepository.findByUserIdAndContentIdAndWatchNumber(ownerId, contentId, 1))
                .thenReturn(Optional.empty());
        when(ratingRepository.findByUserIdAndContentId(ownerId, contentId))
                .thenReturn(Optional.of(current));
        when(passRatingRepository.saveAndFlush(any(ContentRatingPass.class)))
                .thenAnswer(invocation -> {
                    ContentRatingPass pass = invocation.getArgument(0);
                    return ContentRatingPass.builder()
                            .id(restoredPassRatingId)
                            .userId(pass.getUserId())
                            .contentId(pass.getContentId())
                            .contentType(pass.getContentType())
                            .watchNumber(pass.getWatchNumber())
                            .score(pass.getScore())
                            .createdAt(pass.getCreatedAt())
                            .updatedAt(pass.getUpdatedAt())
                            .build();
                });

        Optional<UUID> restored = ratingService.restoreInitial(ownerId, reference, 9, evidence);

        assertThat(restored).contains(restoredPassRatingId);
        verify(passRatingRepository).saveAndFlush(ArgumentMatchers.argThat(pass ->
                pass.getWatchNumber() == 1 && pass.getScore() == 9));
        verify(ratingRepository, never()).saveAndFlush(any(ContentRating.class));
    }

    private ContentRatingPass pass(
            UUID ownerId, UUID contentId, int watchNumber, int score, Instant at) {
        return ContentRatingPass.builder()
                .id(UUID.randomUUID())
                .userId(ownerId)
                .contentId(contentId)
                .contentType(ContentType.SERIES)
                .watchNumber(watchNumber)
                .score(score)
                .createdAt(at)
                .updatedAt(at)
                .build();
    }

    @Test
    void staleExpectedRatingVersionCannotOverwriteCurrentRating() {
        UUID ownerId = UUID.randomUUID();
        UUID contentId = UUID.randomUUID();
        Instant now = Instant.parse("2026-10-23T12:00:00Z");
        Content content = Content.builder()
                .id(contentId)
                .tmdbId("123")
                .type(ContentType.SERIES)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
        ContentRating current = ContentRating.builder()
                .id(UUID.randomUUID())
                .userId(ownerId)
                .contentId(contentId)
                .contentType(ContentType.SERIES)
                .score(8)
                .version(2L)
                .createdAt(now)
                .updatedAt(now)
                .build();
        when(contentRepository.findById(contentId)).thenReturn(Optional.of(content));
        when(ratingRepository.findByUserIdAndContentId(ownerId, contentId)).thenReturn(Optional.of(current));
        when(opinionEvidenceService.capture(eq(ownerId), eq(content), eq(true), any(Instant.class)))
                .thenReturn(new OpinionEvidenceService.Snapshot(
                        new OpinionEligibility(true, false, false),
                        4L,
                        0L,
                        null,
                        Set.of(UUID.randomUUID()),
                        Set.of(),
                        true));

        assertThatThrownBy(() -> ratingService.set(ownerId, contentId, 7, 1L))
                .isInstanceOf(ConflictException.class);

        verify(userContentMutationLock).lock(ownerId);
        verify(ratingRepository, never()).saveAndFlush(any(ContentRating.class));
    }

    @Test
    void restoreInitialRequiresVerifiedHistoricalPassAndCreatesOnlyMissingRating() {
        UUID ownerId = UUID.randomUUID();
        UUID contentId = UUID.randomUUID();
        UUID passRatingId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        MediaReference reference = MediaReference.series("tv-123");
        HistoricalViewingEvidence evidence = new HistoricalViewingEvidence(
                reference, 1, "scope-hash", Set.of(eventId));
        Content content = Content.builder()
                .id(contentId)
                .tmdbId("tv-123")
                .type(ContentType.SERIES)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
        HistoricalViewingEvidenceVerifier.Verification verification =
                new HistoricalViewingEvidenceVerifier.Verification(
                        3L, 4L, Instant.now().plusSeconds(3600), true, Set.of(eventId));
        when(contentRepository.findByTmdbIdAndType("tv-123", ContentType.SERIES)).thenReturn(Optional.of(content));
        when(historicalEvidenceVerifier.verify(eq(ownerId), eq(content), eq(evidence), eq(true), any(Instant.class)))
                .thenReturn(verification);
        when(ratingRepository.findByUserIdAndContentId(ownerId, contentId)).thenReturn(Optional.empty());
        when(passRatingRepository.saveAndFlush(any(ContentRatingPass.class)))
                .thenAnswer(invocation -> {
                    ContentRatingPass pass = invocation.getArgument(0);
                    return ContentRatingPass.builder()
                            .id(passRatingId)
                            .userId(pass.getUserId())
                            .contentId(pass.getContentId())
                            .contentType(pass.getContentType())
                            .watchNumber(pass.getWatchNumber())
                            .score(pass.getScore())
                            .createdAt(pass.getCreatedAt())
                            .updatedAt(pass.getUpdatedAt())
                            .build();
                });
        when(ratingRepository.saveAndFlush(any(ContentRating.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        assertThat(ratingService.restoreInitial(ownerId, reference, 7, evidence)).contains(passRatingId);

        verify(userContentMutationLock).lock(ownerId);
        verify(historicalEvidenceVerifier).revalidate(ownerId, content, evidence, verification);
        verify(passRatingRepository).saveAndFlush(ArgumentMatchers.argThat(pass ->
                pass.getScore() == 7 && pass.getWatchNumber() == 1 && pass.getContentId().equals(contentId)));
        verify(ratingRepository).saveAndFlush(ArgumentMatchers.argThat(rating ->
                rating.getScore() == 7 && rating.getWatchNumber() == 1 && rating.getContentId().equals(contentId)));
    }

    @Test
    void restoreInitialRejectsExpiryDuringRatingReadBeforeWriting() {
        UUID ownerId = UUID.randomUUID();
        UUID contentId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        MediaReference reference = MediaReference.series("tv-expiry");
        HistoricalViewingEvidence evidence = new HistoricalViewingEvidence(
                reference, 1, "scope-hash", Set.of(eventId));
        Content content = Content.builder()
                .id(contentId)
                .tmdbId("tv-expiry")
                .type(ContentType.SERIES)
                .build();
        Instant validUntil = Instant.parse("2030-01-01T00:00:00Z");
        AtomicReference<Instant> currentTime = new AtomicReference<>(validUntil.minusSeconds(1));
        HistoricalViewingEvidenceVerifier.Verification verification =
                new HistoricalViewingEvidenceVerifier.Verification(3L, 4L, validUntil, true, Set.of(eventId));
        when(contentRepository.findByTmdbIdAndType("tv-expiry", ContentType.SERIES))
                .thenReturn(Optional.of(content));
        when(historicalEvidenceVerifier.verify(eq(ownerId), eq(content), eq(evidence), eq(true), any(Instant.class)))
                .thenReturn(verification);
        when(ratingRepository.findByUserIdAndContentId(ownerId, contentId)).thenAnswer(invocation -> {
            currentTime.set(validUntil.plusNanos(1));
            return Optional.empty();
        });
        doAnswer(invocation -> {
            if (currentTime.get().isAfter(verification.validUntil())) {
                throw new ConflictException("Diary completion scope expired");
            }
            return null;
        }).when(historicalEvidenceVerifier).revalidate(ownerId, content, evidence, verification);

        assertThatThrownBy(() -> ratingService.restoreInitial(ownerId, reference, 7, evidence))
                .isInstanceOf(ConflictException.class);

        var order = inOrder(ratingRepository, historicalEvidenceVerifier);
        order.verify(ratingRepository).findByUserIdAndContentId(ownerId, contentId);
        order.verify(historicalEvidenceVerifier).revalidate(ownerId, content, evidence, verification);
        verify(ratingRepository, never()).saveAndFlush(any(ContentRating.class));
    }
}
