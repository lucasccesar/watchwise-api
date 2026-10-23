package com.watchwise.watchwise_api.contentrating.service.impl;

import com.watchwise.watchwise_api.contentrating.service.OpinionEligibility;
import com.watchwise.watchwise_api.contentrating.service.OpinionEvidenceService;
import com.watchwise.watchwise_api.contentrating.entity.ContentRating;
import com.watchwise.watchwise_api.contentrating.repository.ContentRatingRepository;
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
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.ArgumentMatchers;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ContentRatingServiceTest {

    @Mock
    private ContentRatingRepository ratingRepository;

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
        when(opinionEvidenceService.capture(eq(ownerId), eq(content), eq(false), any(Instant.class)))
                .thenReturn(new OpinionEvidenceService.Snapshot(
                        new OpinionEligibility(true, false, false),
                        4L,
                        0L,
                        null,
                        Set.of(UUID.randomUUID()),
                        Set.of(),
                        false));

        assertThatThrownBy(() -> ratingService.set(ownerId, contentId, 7, 1L))
                .isInstanceOf(ConflictException.class);

        verify(userContentMutationLock).lock(ownerId);
        verify(ratingRepository, never()).saveAndFlush(any(ContentRating.class));
    }

    @Test
    void restoreInitialRequiresVerifiedHistoricalPassAndCreatesOnlyMissingRating() {
        UUID ownerId = UUID.randomUUID();
        UUID contentId = UUID.randomUUID();
        UUID ratingId = UUID.randomUUID();
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
                new HistoricalViewingEvidenceVerifier.Verification(3L, 4L, true, Set.of(eventId));
        when(contentRepository.findByTmdbIdAndType("tv-123", ContentType.SERIES)).thenReturn(Optional.of(content));
        when(historicalEvidenceVerifier.verify(eq(ownerId), eq(content), eq(evidence), eq(true), any(Instant.class)))
                .thenReturn(verification);
        when(ratingRepository.findByUserIdAndContentId(ownerId, contentId)).thenReturn(Optional.empty());
        when(ratingRepository.saveAndFlush(any(ContentRating.class)))
                .thenAnswer(invocation -> ContentRating.builder().id(ratingId).build());

        assertThat(ratingService.restoreInitial(ownerId, reference, 7, evidence)).contains(ratingId);

        verify(userContentMutationLock).lock(ownerId);
        verify(historicalEvidenceVerifier).revalidate(ownerId, content, evidence, verification);
        verify(ratingRepository).saveAndFlush(ArgumentMatchers.argThat(rating ->
                rating.getScore() == 7 && rating.getContentId().equals(contentId)));
    }
}
