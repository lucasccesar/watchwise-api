package com.watchwise.watchwise_api.contentreview.service.impl;

import com.watchwise.watchwise_api.common.exception.BadRequestException;
import com.watchwise.watchwise_api.common.exception.ConflictException;
import com.watchwise.watchwise_api.common.exception.NotFoundException;
import com.watchwise.watchwise_api.common.idempotency.UserActionExecutor;
import com.watchwise.watchwise_api.common.idempotency.UserActionOperation;
import com.watchwise.watchwise_api.common.transaction.UserContentMutationLock;
import com.watchwise.watchwise_api.content.entity.Content;
import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.content.repository.ContentRepository;
import com.watchwise.watchwise_api.contentrating.entity.ContentRating;
import com.watchwise.watchwise_api.contentrating.repository.ContentRatingRepository;
import com.watchwise.watchwise_api.contentrating.service.OpinionEvidenceService;
import com.watchwise.watchwise_api.contentreview.dto.ContentReviewCreationDTO;
import com.watchwise.watchwise_api.contentreview.dto.ContentReviewPatchDTO;
import com.watchwise.watchwise_api.contentreview.dto.ContentReviewViewDTO;
import com.watchwise.watchwise_api.contentreview.entity.ContentReview;
import com.watchwise.watchwise_api.contentreview.entity.ReviewContextKind;
import com.watchwise.watchwise_api.contentreview.entity.ReviewPublicationKind;
import com.watchwise.watchwise_api.contentreview.mapper.ContentReviewMapper;
import com.watchwise.watchwise_api.contentreview.repository.ContentReviewRepository;
import com.watchwise.watchwise_api.contentreview.service.ContentReviewService;
import com.watchwise.watchwise_api.contentreview.service.ReviewPublicationOrder;
import lombok.RequiredArgsConstructor;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ContentReviewServiceImpl implements ContentReviewService {

    private static final int MAX_IDEMPOTENCY_KEY_LENGTH = 128;

    private final ContentReviewRepository reviewRepository;
    private final ContentRatingRepository ratingRepository;
    private final ContentRepository contentRepository;
    private final OpinionEvidenceService opinionEvidenceService;
    private final UserContentMutationLock userContentMutationLock;
    private final UserActionExecutor userActionExecutor;
    private final ContentReviewMapper contentReviewMapper;
    private final ReviewPublicationOrder publicationOrder;
    private final ObjectMapper objectMapper;

    @Override
    @Transactional
    public ContentReviewViewDTO create(
            UUID ownerId,
            UUID contentId,
            ContentReviewCreationDTO creation,
            String idempotencyKey) {
        validateCreation(creation, idempotencyKey);
        Content content = requireOpinionContent(contentId);
        String requestHash = requestHash(contentId, creation);
        UserActionExecutor.ActionResult result = userActionExecutor.execute(
                ownerId,
                UserActionOperation.CONTENT_REVIEW_PUBLICATION,
                idempotencyKey,
                requestHash,
                () -> publish(ownerId, content, creation));
        return contentReviewMapper.toView(findOwnedReview(ownerId, result.resourceIds().getFirst()));
    }

    @Override
    @Transactional(readOnly = true)
    public ContentReviewViewDTO get(UUID ownerId, UUID reviewId) {
        return contentReviewMapper.toView(findOwnedReview(ownerId, reviewId));
    }

    @Override
    @Transactional
    public ContentReviewViewDTO edit(UUID ownerId, UUID reviewId, ContentReviewPatchDTO patch) {
        validatePatch(patch);
        findOwnedReview(ownerId, reviewId);
        userContentMutationLock.lock(ownerId);
        ContentReview review = findOwnedReview(ownerId, reviewId);
        review.setComment(patch.comment());
        review.setUpdatedAt(Instant.now());
        try {
            return contentReviewMapper.toView(reviewRepository.saveAndFlush(review));
        } catch (OptimisticLockingFailureException conflict) {
            throw new ConflictException("Review changed while it was being updated");
        }
    }

    @Override
    @Transactional
    public void delete(UUID ownerId, UUID reviewId) {
        findOwnedReview(ownerId, reviewId);
        userContentMutationLock.lock(ownerId);
        reviewRepository.delete(findOwnedReview(ownerId, reviewId));
    }

    private UserActionExecutor.ActionResult publish(
            UUID ownerId,
            Content content,
            ContentReviewCreationDTO creation) {
        Instant evidenceTime = Instant.now();
        OpinionEvidenceService.Snapshot evidence = opinionEvidenceService.capture(
                ownerId, content, true, evidenceTime);
        if (!evidence.eligibility().completeNow() || evidence.completedWatchNumber() == null) {
            throw new BadRequestException("A written review requires a currently completed pass");
        }

        userContentMutationLock.lock(ownerId);
        Instant publishedAt = Instant.now();
        opinionEvidenceService.revalidate(ownerId, content, evidence, publishedAt);
        if (content.getType() == ContentType.SEASON
                && reviewRepository.existsByUserIdAndContentIdAndWatchNumberAndPublicationKind(
                ownerId, content.getId(), evidence.completedWatchNumber(), ReviewPublicationKind.WRITTEN_REVIEW)) {
            throw new ConflictException("A written review already exists for this season pass");
        }

        ContentRating rating = creation.score() == null
                ? null
                : ratingRepository.findByUserIdAndContentId(ownerId, content.getId()).orElse(null);
        if (creation.score() != null) {
            assertExpectedRatingVersion(rating, creation.expectedRatingVersion());
        } else if (creation.expectedRatingVersion() != null) {
            throw new BadRequestException("expectedRatingVersion requires a review score");
        }

        ContentReview review = ContentReview.builder()
                .userId(ownerId)
                .contentId(content.getId())
                .contentType(content.getType())
                .publicationKind(ReviewPublicationKind.WRITTEN_REVIEW)
                .contextKind(ReviewContextKind.COMPLETION)
                .contextJson(completionContext(content, evidence))
                .comment(creation.comment())
                .scoreAtPublication(creation.score())
                .watchNumber(evidence.completedWatchNumber())
                .sourcePublishedAt(publishedAt)
                .createdAt(publishedAt)
                .updatedAt(publishedAt)
                .build();
        List<ContentReview> existingPublicationBoundary = creation.score() == null
                ? List.of()
                : publicationBoundary(ownerId, content.getId());

        try {
            review = reviewRepository.saveAndFlush(review);
            if (creation.score() != null && publicationOrder.isNewLatest(review, existingPublicationBoundary)) {
                updateCurrentRating(ownerId, content, rating, creation.score(), publishedAt);
            }
        } catch (DataIntegrityViolationException conflict) {
            throw conflict.getCause() instanceof ConstraintViolationException violation
                    && "uq_content_reviews_season_pass".equals(violation.getConstraintName())
                    ? new ConflictException("A written review already exists for this season pass")
                    : new ConflictException("Review could not be saved due to a conflict");
        } catch (OptimisticLockingFailureException conflict) {
            throw new ConflictException("Rating or review changed while the review was being published");
        }
        return new UserActionExecutor.ActionResult(List.of(review.getId()), review.getVersion());
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

    private void updateCurrentRating(
            UUID ownerId,
            Content content,
            ContentRating rating,
            Integer score,
            Instant publishedAt) {
        ContentRating updated = rating;
        if (updated == null) {
            updated = ContentRating.builder()
                    .userId(ownerId)
                    .contentId(content.getId())
                    .contentType(content.getType())
                    .score(score)
                    .createdAt(publishedAt)
                    .updatedAt(publishedAt)
                    .build();
        } else {
            updated.setScore(score);
            updated.setUpdatedAt(publishedAt);
        }
        ratingRepository.saveAndFlush(updated);
    }

    private String completionContext(Content content, OpinionEvidenceService.Snapshot evidence) {
        return objectMapper.writeValueAsString(new CompletionContext(
                content.getType().name(),
                content.getTmdbId(),
                content.getSeriesTmdbId(),
                content.getSeasonNumber(),
                evidence.completedWatchNumber(),
                evidence.historyRevision(),
                evidence.scheduleVersion(),
                evidence.completionEventIds().stream().sorted().toList()));
    }

    private Content requireOpinionContent(UUID contentId) {
        Content content = contentRepository.findById(contentId)
                .orElseThrow(() -> new NotFoundException("Content not found"));
        if ((content.getType() != ContentType.SERIES && content.getType() != ContentType.SEASON)
                || (content.getType() == ContentType.SEASON
                && (content.getSeasonNumber() == null || content.getSeasonNumber() <= 0))) {
            throw new BadRequestException("Written reviews require a series or regular season");
        }
        return content;
    }

    private ContentReview findOwnedReview(UUID ownerId, UUID reviewId) {
        return reviewRepository.findById(reviewId)
                .filter(review -> ownerId.equals(review.getUserId())
                        && review.getPublicationKind() == ReviewPublicationKind.WRITTEN_REVIEW)
                .orElseThrow(() -> new NotFoundException("Review not found"));
    }

    private void validateCreation(ContentReviewCreationDTO creation, String idempotencyKey) {
        if (creation == null || creation.comment() == null || creation.comment().isBlank()
                || creation.comment().length() > 20000
                || (creation.score() != null && (creation.score() < 1 || creation.score() > 10))
                || (creation.expectedRatingVersion() != null && creation.expectedRatingVersion() < 0)) {
            throw new BadRequestException("Review request is invalid");
        }
        if (idempotencyKey == null || idempotencyKey.isBlank()
                || idempotencyKey.length() > MAX_IDEMPOTENCY_KEY_LENGTH) {
            throw new BadRequestException("Idempotency-Key is required and must not exceed 128 characters");
        }
    }

    private void validatePatch(ContentReviewPatchDTO patch) {
        if (patch == null || patch.comment() == null || patch.comment().isBlank() || patch.comment().length() > 20000) {
            throw new BadRequestException("Review comment is invalid");
        }
    }

    private void assertExpectedRatingVersion(ContentRating rating, Long expectedVersion) {
        if (rating == null && expectedVersion != null) {
            throw new ConflictException("Rating no longer exists");
        }
        if (rating != null && (expectedVersion == null || !expectedVersion.equals(rating.getVersion()))) {
            throw new ConflictException("Rating version is stale or missing");
        }
    }

    private String requestHash(UUID contentId, ContentReviewCreationDTO creation) {
        String canonical = contentId + "\n" + creation.comment() + "\n"
                + creation.score() + "\n" + creation.expectedRatingVersion();
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private record CompletionContext(
            String contentType,
            String tmdbId,
            String seriesTmdbId,
            Integer seasonNumber,
            Integer watchNumber,
            long historyRevision,
            long scheduleVersion,
            List<UUID> eventIds) {
    }
}
