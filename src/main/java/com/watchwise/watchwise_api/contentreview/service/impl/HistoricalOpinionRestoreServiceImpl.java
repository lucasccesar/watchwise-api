package com.watchwise.watchwise_api.contentreview.service.impl;

import com.watchwise.watchwise_api.common.exception.BadRequestException;
import com.watchwise.watchwise_api.common.exception.ConflictException;
import com.watchwise.watchwise_api.common.exception.NotFoundException;
import com.watchwise.watchwise_api.common.transaction.UserContentMutationLock;
import com.watchwise.watchwise_api.content.dto.MediaReference;
import com.watchwise.watchwise_api.content.entity.Content;
import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.content.repository.ContentRepository;
import com.watchwise.watchwise_api.contentrating.entity.ContentRating;
import com.watchwise.watchwise_api.contentrating.repository.ContentRatingRepository;
import com.watchwise.watchwise_api.contentreview.entity.ContentReview;
import com.watchwise.watchwise_api.contentreview.entity.ReviewContextKind;
import com.watchwise.watchwise_api.contentreview.entity.ReviewPublicationKind;
import com.watchwise.watchwise_api.contentreview.repository.ContentReviewRepository;
import com.watchwise.watchwise_api.contentreview.service.HistoricalOpinion;
import com.watchwise.watchwise_api.contentreview.service.HistoricalOpinionRestoreService;
import com.watchwise.watchwise_api.contentreview.service.HistoricalViewingEvidence;
import com.watchwise.watchwise_api.contentreview.service.HistoricalViewingEvidenceVerifier;
import com.watchwise.watchwise_api.contentreview.service.ReviewPublicationOrder;
import lombok.RequiredArgsConstructor;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class HistoricalOpinionRestoreServiceImpl implements HistoricalOpinionRestoreService {

    private final ContentRepository contentRepository;
    private final ContentReviewRepository reviewRepository;
    private final ContentRatingRepository ratingRepository;
    private final HistoricalViewingEvidenceVerifier evidenceVerifier;
    private final UserContentMutationLock userContentMutationLock;
    private final ReviewPublicationOrder publicationOrder;

    @Override
    @Transactional
    public Optional<UUID> restore(
            UUID ownerId,
            HistoricalOpinion opinion,
            HistoricalViewingEvidence evidence) {
        if (opinion == null || opinion.comment() == null || opinion.comment().isBlank()) {
            return Optional.empty();
        }
        if (opinion.comment().length() > 20000
                || (opinion.scoreAtPublication() != null
                && (opinion.scoreAtPublication() < 1 || opinion.scoreAtPublication() > 10))) {
            throw new BadRequestException("Historical opinion is invalid");
        }
        if (evidence == null || !opinion.content().equals(evidence.content())
                || opinion.watchNumber() != evidence.watchNumber()) {
            throw new BadRequestException("Historical viewing evidence does not match the opinion");
        }

        Content content = resolveContent(opinion.content());
        Instant now = Instant.now();
        HistoricalViewingEvidenceVerifier.Verification verification = evidenceVerifier.verify(
                ownerId, content, evidence, false, now);
        userContentMutationLock.lock(ownerId);
        if (content.getType() == ContentType.SEASON
                && reviewRepository.existsByUserIdAndContentIdAndWatchNumberAndPublicationKind(
                ownerId, content.getId(), opinion.watchNumber(), ReviewPublicationKind.WRITTEN_REVIEW)) {
            throw new ConflictException("A written review already exists for this season pass");
        }

        ReviewContextKind contextKind = verification.completionScopeProven()
                ? ReviewContextKind.COMPLETION
                : ReviewContextKind.UNKNOWN;
        ContentReview review = ContentReview.builder()
                .userId(ownerId)
                .contentId(content.getId())
                .contentType(content.getType())
                .publicationKind(ReviewPublicationKind.WRITTEN_REVIEW)
                .contextKind(contextKind)
                .contextJson(contextKind == ReviewContextKind.COMPLETION ? opinion.contextJson() : null)
                .comment(opinion.comment())
                .scoreAtPublication(opinion.scoreAtPublication())
                .watchNumber(opinion.watchNumber())
                .sourcePublishedAt(opinion.sourcePublishedAt())
                .sourcePublishedOn(opinion.sourcePublishedOn())
                .createdAt(now)
                .updatedAt(now)
                .build();

        List<ContentReview> existingBoundary = opinion.scoreAtPublication() == null
                ? List.of()
                : publicationBoundary(ownerId, content.getId());
        boolean updateCurrentRating = opinion.scoreAtPublication() != null
                && publicationOrder.isNewLatest(review, existingBoundary);
        ContentRating currentRating = updateCurrentRating
                ? findCurrentRating(ownerId, content.getId())
                : null;
        evidenceVerifier.revalidate(ownerId, content, evidence, verification);
        try {
            review = reviewRepository.saveAndFlush(review);
            if (updateCurrentRating) {
                updateCurrentRating(ownerId, content, currentRating, opinion.scoreAtPublication(), now);
            }
        } catch (DataIntegrityViolationException conflict) {
            throw conflict.getCause() instanceof ConstraintViolationException violation
                    && "uq_content_reviews_season_pass".equals(violation.getConstraintName())
                    ? new ConflictException("A written review already exists for this season pass")
                    : new ConflictException("Historical review could not be restored due to a conflict");
        } catch (OptimisticLockingFailureException conflict) {
            throw new ConflictException("Rating or review changed while historical opinion was restored");
        }
        return Optional.of(review.getId());
    }

    private Content resolveContent(MediaReference reference) {
        if (reference == null || (reference.type() != ContentType.SERIES && reference.type() != ContentType.SEASON)
                || (reference.type() == ContentType.SEASON
                && (reference.seasonNumber() == null || reference.seasonNumber() <= 0))) {
            throw new BadRequestException("Historical opinions require a series or regular season");
        }
        return switch (reference.type()) {
            case SERIES -> contentRepository.findByTmdbIdAndType(reference.tmdbId(), ContentType.SERIES)
                    .orElseThrow(() -> new NotFoundException("Content not found"));
            case SEASON -> contentRepository.findByTypeAndSeriesTmdbIdAndSeasonNumberIn(
                            ContentType.SEASON, reference.seriesTmdbId(), List.of(reference.seasonNumber())).stream()
                    .findFirst()
                    .orElseThrow(() -> new NotFoundException("Content not found"));
            default -> throw new BadRequestException("Historical opinions require a series or regular season");
        };
    }

    private List<ContentReview> publicationBoundary(UUID ownerId, UUID contentId) {
        List<ContentReview> boundary = new ArrayList<>(reviewRepository
                .findLatestScoredPublicationBoundary(ownerId, contentId));
        if (reviewRepository
                .existsByUserIdAndContentIdAndScoreAtPublicationIsNotNullAndSourcePublishedAtIsNullAndSourcePublishedOnIsNull(
                        ownerId, contentId)) {
            boundary.add(ContentReview.builder().scoreAtPublication(1).build());
        }
        return boundary;
    }

    private ContentRating findCurrentRating(UUID ownerId, UUID contentId) {
        try {
            return ratingRepository.findByUserIdAndContentId(ownerId, contentId).orElse(null);
        } catch (DataIntegrityViolationException conflict) {
            throw new ConflictException("Historical review could not be restored due to a conflict");
        } catch (OptimisticLockingFailureException conflict) {
            throw new ConflictException("Rating or review changed while historical opinion was restored");
        }
    }

    private void updateCurrentRating(
            UUID ownerId, Content content, ContentRating rating, Integer score, Instant now) {
        if (rating == null) {
            rating = ContentRating.builder()
                    .userId(ownerId)
                    .contentId(content.getId())
                    .contentType(content.getType())
                    .score(score)
                    .createdAt(now)
                    .updatedAt(now)
                    .build();
        } else {
            rating.setScore(score);
            rating.setUpdatedAt(now);
        }
        ratingRepository.saveAndFlush(rating);
    }
}
