package com.watchwise.watchwise_api.contentreview.service;

import com.watchwise.watchwise_api.contentreview.entity.ContentReview;
import com.watchwise.watchwise_api.contentreview.entity.ReviewContextKind;
import com.watchwise.watchwise_api.contentreview.entity.ReviewPublicationKind;
import com.watchwise.watchwise_api.contentrating.entity.ContentRating;
import com.watchwise.watchwise_api.contentrating.repository.ContentRatingRepository;
import com.watchwise.watchwise_api.contentreview.repository.ContentReviewRepository;
import com.watchwise.watchwise_api.contentreview.service.impl.ContentReviewServiceImpl;
import com.watchwise.watchwise_api.contentreview.service.impl.HistoricalOpinionRestoreServiceImpl;
import com.watchwise.watchwise_api.contentreview.service.HistoricalOpinion;
import com.watchwise.watchwise_api.contentreview.service.HistoricalViewingEvidence;
import com.watchwise.watchwise_api.contentreview.service.HistoricalViewingEvidenceVerifier;
import com.watchwise.watchwise_api.common.transaction.UserContentMutationLock;
import com.watchwise.watchwise_api.common.exception.ConflictException;
import com.watchwise.watchwise_api.content.dto.MediaReference;
import com.watchwise.watchwise_api.content.entity.Content;
import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.content.repository.ContentRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import org.mockito.ArgumentCaptor;

import java.util.concurrent.atomic.AtomicReference;

@ExtendWith(MockitoExtension.class)
class ContentReviewServiceTest {

    @Mock
    private ContentReviewRepository reviewRepository;

    @Mock
    private ContentRatingRepository ratingRepository;

    @Mock
    private UserContentMutationLock mutationLock;

    @Mock
    private ContentRepository contentRepository;

    @Mock
    private HistoricalViewingEvidenceVerifier evidenceVerifier;

    @Mock
    private ReviewPublicationOrder publicationOrderBean;

    @InjectMocks
    private ContentReviewServiceImpl reviewService;

    @InjectMocks
    private HistoricalOpinionRestoreServiceImpl historicalOpinionRestoreService;

    private final ReviewPublicationOrder publicationOrder = new ReviewPublicationOrder();

    @Test
    void deletingWrittenReviewLeavesIndependentCurrentRatingUntouched() {
        UUID ownerId = UUID.randomUUID();
        UUID reviewId = UUID.randomUUID();
        ContentReview review = ContentReview.builder()
                .id(reviewId)
                .userId(ownerId)
                .publicationKind(ReviewPublicationKind.WRITTEN_REVIEW)
                .build();
        when(reviewRepository.findById(reviewId)).thenReturn(Optional.of(review));

        reviewService.delete(ownerId, reviewId);

        verify(mutationLock).lock(ownerId);
        verify(reviewRepository).delete(review);
        verifyNoInteractions(ratingRepository);
    }

    @Test
    void publicationOrderPreservesDayPrecisionAndRejectsUnprovableLatestReviews() {
        ContentReview current = review(Instant.parse("2026-10-23T14:00:00Z"), null, 8);
        ContentReview earlierDay = review(null, LocalDate.parse("2026-10-22"), 7);
        ContentReview sameDay = review(null, LocalDate.parse("2026-10-23"), 9);
        ContentReview latestTimestamp = review(Instant.parse("2026-10-24T08:00:00Z"), null, 6);
        ContentReview sameDateOnly = review(null, LocalDate.parse("2026-10-23"), 5);

        assertThat(publicationOrder.compare(current, List.of(earlierDay)))
                .isEqualTo(ReviewPublicationOrder.Order.NEWER);
        assertThat(publicationOrder.compare(current, List.of(sameDay)))
                .isEqualTo(ReviewPublicationOrder.Order.AMBIGUOUS);
        assertThat(publicationOrder.compare(current, List.of(latestTimestamp)))
                .isEqualTo(ReviewPublicationOrder.Order.OLDER);
        assertThat(publicationOrder.compare(current, List.of(sameDateOnly)))
                .isEqualTo(ReviewPublicationOrder.Order.AMBIGUOUS);
        assertThat(publicationOrder.isNewLatest(earlierDay, List.of(current))).isFalse();
    }

    @Test
    void noteOnlyHistoricalOpinionDoesNotCreateAnEmptyReviewOrSyntheticId() {
        HistoricalOpinion opinion = new HistoricalOpinion(
                MediaReference.series("tv-1"), 1, "  ", 8, null, LocalDate.parse("2025-01-01"), null);

        assertThat(historicalOpinionRestoreService.restore(UUID.randomUUID(), opinion, null)).isEmpty();

        verifyNoInteractions(reviewRepository, ratingRepository, contentRepository, evidenceVerifier, mutationLock);
    }

    @Test
    void historicalRestorePreservesSourcePrecisionAndMarksUnprovenContextUnknown() {
        UUID ownerId = UUID.randomUUID();
        UUID reviewId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        MediaReference reference = MediaReference.series("tv-1");
        Content content = Content.builder()
                .id(UUID.randomUUID())
                .tmdbId("tv-1")
                .type(ContentType.SERIES)
                .build();
        Instant publishedAt = Instant.parse("2024-06-07T08:09:10.123456Z");
        HistoricalOpinion opinion = new HistoricalOpinion(
                reference, 2, "Imported review", null, publishedAt, null, "{\"source\":\"history\"}");
        HistoricalViewingEvidence evidence = new HistoricalViewingEvidence(
                reference, 2, "historical-scope", Set.of(eventId));
        Instant validUntil = Instant.parse("2027-01-01T00:00:00Z");
        when(contentRepository.findByTmdbIdAndType("tv-1", ContentType.SERIES)).thenReturn(Optional.of(content));
        when(evidenceVerifier.verify(eq(ownerId), eq(content), eq(evidence), eq(false), any(Instant.class)))
                .thenReturn(new HistoricalViewingEvidenceVerifier.Verification(
                        3L, 0L, validUntil, false, Set.of(eventId)));
        when(reviewRepository.saveAndFlush(any(ContentReview.class)))
                .thenReturn(ContentReview.builder().id(reviewId).build());

        assertThat(historicalOpinionRestoreService.restore(ownerId, opinion, evidence)).contains(reviewId);

        ArgumentCaptor<ContentReview> saved = ArgumentCaptor.forClass(ContentReview.class);
        verify(reviewRepository).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getPublicationKind()).isEqualTo(ReviewPublicationKind.WRITTEN_REVIEW);
        assertThat(saved.getValue().getContextKind()).isEqualTo(ReviewContextKind.UNKNOWN);
        assertThat(saved.getValue().getContextJson()).isNull();
        assertThat(saved.getValue().getSourcePublishedAt()).isEqualTo(publishedAt);
        assertThat(saved.getValue().getSourcePublishedOn()).isNull();
        verify(evidenceVerifier).revalidate(ownerId, content, evidence,
                new HistoricalViewingEvidenceVerifier.Verification(3L, 0L, validUntil, false, Set.of(eventId)));
    }

    @Test
    void historicalRestoreRejectsExpiryDuringPostVerificationReadsBeforeWriting() {
        UUID ownerId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        UUID contentId = UUID.randomUUID();
        MediaReference reference = MediaReference.season("tv-expiry", 1);
        Content content = Content.builder()
                .id(contentId)
                .seriesTmdbId("tv-expiry")
                .seasonNumber(1)
                .type(ContentType.SEASON)
                .build();
        HistoricalOpinion opinion = new HistoricalOpinion(
                reference, 2, "Imported season review", 7, Instant.parse("2024-01-01T12:00:00Z"), null, null);
        HistoricalViewingEvidence evidence = new HistoricalViewingEvidence(
                reference, 2, "historical-scope", Set.of(eventId));
        Instant validUntil = Instant.parse("2030-01-01T00:00:00Z");
        AtomicReference<Instant> currentTime = new AtomicReference<>(validUntil.minusSeconds(1));
        HistoricalViewingEvidenceVerifier.Verification verification =
                new HistoricalViewingEvidenceVerifier.Verification(3L, 0L, validUntil, true, Set.of(eventId));
        when(contentRepository.findByTypeAndSeriesTmdbIdAndSeasonNumberIn(
                ContentType.SEASON, "tv-expiry", List.of(1))).thenReturn(List.of(content));
        when(evidenceVerifier.verify(eq(ownerId), eq(content), eq(evidence), eq(false), any(Instant.class)))
                .thenReturn(verification);
        when(publicationOrderBean.isNewLatest(any(ContentReview.class), any())).thenReturn(true);
        when(reviewRepository.findLatestScoredPublicationBoundary(ownerId, contentId)).thenAnswer(invocation -> {
            currentTime.set(validUntil.plusNanos(1));
            return List.of();
        });
        when(reviewRepository
                .existsByUserIdAndContentIdAndScoreAtPublicationIsNotNullAndSourcePublishedAtIsNullAndSourcePublishedOnIsNull(
                        ownerId, contentId))
                .thenReturn(false);
        when(ratingRepository.findByUserIdAndContentId(ownerId, contentId)).thenAnswer(invocation -> {
            currentTime.set(validUntil.plusNanos(1));
            return Optional.empty();
        });
        doAnswer(invocation -> {
            if (currentTime.get().isAfter(verification.validUntil())) {
                throw new ConflictException("Diary completion scope expired");
            }
            return null;
        }).when(evidenceVerifier).revalidate(ownerId, content, evidence, verification);

        assertThatThrownBy(() -> historicalOpinionRestoreService.restore(ownerId, opinion, evidence))
                .isInstanceOf(ConflictException.class);

        var order = inOrder(reviewRepository, ratingRepository, evidenceVerifier);
        order.verify(reviewRepository).existsByUserIdAndContentIdAndWatchNumberAndPublicationKind(
                        ownerId, contentId, 2, ReviewPublicationKind.WRITTEN_REVIEW);
        order.verify(reviewRepository).findLatestScoredPublicationBoundary(ownerId, contentId);
        order.verify(reviewRepository)
                .existsByUserIdAndContentIdAndScoreAtPublicationIsNotNullAndSourcePublishedAtIsNullAndSourcePublishedOnIsNull(
                        ownerId, contentId);
        order.verify(ratingRepository).findByUserIdAndContentId(ownerId, contentId);
        order.verify(evidenceVerifier).revalidate(ownerId, content, evidence, verification);
        verify(reviewRepository, never()).saveAndFlush(any(ContentReview.class));
        verify(ratingRepository, never()).saveAndFlush(any(ContentRating.class));
    }

    private ContentReview review(Instant publishedAt, LocalDate publishedOn, int score) {
        return ContentReview.builder()
                .scoreAtPublication(score)
                .sourcePublishedAt(publishedAt)
                .sourcePublishedOn(publishedOn)
                .build();
    }
}
