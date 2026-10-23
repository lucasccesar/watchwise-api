package com.watchwise.watchwise_api.contentrating.service.impl;

import com.watchwise.watchwise_api.common.exception.BadRequestException;
import com.watchwise.watchwise_api.common.exception.ConflictException;
import com.watchwise.watchwise_api.common.exception.NotFoundException;
import com.watchwise.watchwise_api.common.transaction.UserContentMutationLock;
import com.watchwise.watchwise_api.content.dto.MediaReference;
import com.watchwise.watchwise_api.content.entity.Content;
import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.content.repository.ContentRepository;
import com.watchwise.watchwise_api.contentrating.dto.ContentRatingResponseDTO;
import com.watchwise.watchwise_api.contentrating.entity.ContentRating;
import com.watchwise.watchwise_api.contentrating.repository.ContentRatingRepository;
import com.watchwise.watchwise_api.contentrating.service.ContentRatingService;
import com.watchwise.watchwise_api.contentrating.service.OpinionEligibility;
import com.watchwise.watchwise_api.contentrating.service.OpinionEvidenceService;
import com.watchwise.watchwise_api.contentreview.service.HistoricalViewingEvidence;
import com.watchwise.watchwise_api.contentreview.service.HistoricalViewingEvidenceVerifier;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class ContentRatingServiceImpl implements ContentRatingService {

    private final ContentRatingRepository ratingRepository;
    private final ContentRepository contentRepository;
    private final OpinionEvidenceService opinionEvidenceService;
    private final HistoricalViewingEvidenceVerifier historicalEvidenceVerifier;
    private final UserContentMutationLock userContentMutationLock;

    public ContentRatingServiceImpl(
            ContentRatingRepository ratingRepository,
            ContentRepository contentRepository,
            OpinionEvidenceService opinionEvidenceService,
            HistoricalViewingEvidenceVerifier historicalEvidenceVerifier,
            UserContentMutationLock userContentMutationLock) {
        this.ratingRepository = ratingRepository;
        this.contentRepository = contentRepository;
        this.opinionEvidenceService = opinionEvidenceService;
        this.historicalEvidenceVerifier = historicalEvidenceVerifier;
        this.userContentMutationLock = userContentMutationLock;
    }

    @Override
    @Transactional
    public ContentRatingResponseDTO set(UUID ownerId, UUID contentId, Integer score, Long expectedVersion) {
        requireScore(score);
        Content content = requireOpinionContent(contentId);
        boolean ratingExistedBeforeEvidenceCollection = ratingRepository
                .findByUserIdAndContentId(ownerId, contentId).isPresent();
        Instant now = Instant.now();
        OpinionEvidenceService.Snapshot evidence = opinionEvidenceService.capture(
                ownerId, content, !ratingExistedBeforeEvidenceCollection, now);

        userContentMutationLock.lock(ownerId);
        opinionEvidenceService.revalidate(ownerId, content, evidence, Instant.now());
        ContentRating rating = ratingRepository.findByUserIdAndContentId(ownerId, contentId).orElse(null);
        assertExpectedVersion(rating, expectedVersion);
        OpinionEligibility proof = new OpinionEligibility(
                evidence.eligibility().anyWatchedEvidence(),
                evidence.eligibility().completeNow(),
                rating != null);
        if (!canSetRating(rating != null, proof)) {
            throw new BadRequestException("A rating requires a completed pass or previously eligible rating with watch evidence");
        }

        Instant updatedAt = Instant.now();
        if (rating == null) {
            rating = ContentRating.builder()
                    .userId(ownerId)
                    .contentId(contentId)
                    .contentType(content.getType())
                    .score(score)
                    .createdAt(updatedAt)
                    .updatedAt(updatedAt)
                    .build();
        } else {
            rating.setScore(score);
            rating.setUpdatedAt(updatedAt);
        }
        try {
            rating = ratingRepository.saveAndFlush(rating);
        } catch (OptimisticLockingFailureException conflict) {
            throw new ConflictException("Rating changed while it was being updated");
        }
        return new ContentRatingResponseDTO(rating.getScore(), rating.getVersion());
    }

    @Override
    @Transactional
    public void remove(UUID ownerId, UUID contentId) {
        requireOpinionContent(contentId);
        userContentMutationLock.lock(ownerId);
        ratingRepository.findByUserIdAndContentId(ownerId, contentId).ifPresent(ratingRepository::delete);
    }

    @Override
    @Transactional
    public Optional<UUID> restoreInitial(
            UUID ownerId,
            MediaReference reference,
            Integer score,
            HistoricalViewingEvidence evidence) {
        requireScore(score);
        if (evidence == null) {
            throw new BadRequestException("Historical viewing evidence is required");
        }
        Content content = resolveContent(reference);
        Instant now = Instant.now();
        HistoricalViewingEvidenceVerifier.Verification verified = historicalEvidenceVerifier.verify(
                ownerId, content, evidence, true, now);

        userContentMutationLock.lock(ownerId);
        if (ratingRepository.findByUserIdAndContentId(ownerId, content.getId()).isPresent()) {
            return Optional.empty();
        }
        ContentRating rating = ContentRating.builder()
                .userId(ownerId)
                .contentId(content.getId())
                .contentType(content.getType())
                .score(score)
                .createdAt(now)
                .updatedAt(now)
                .build();
        historicalEvidenceVerifier.revalidate(ownerId, content, evidence, verified);
        rating = ratingRepository.saveAndFlush(rating);
        return Optional.of(rating.getId());
    }

    private Content requireOpinionContent(UUID contentId) {
        Content content = contentRepository.findById(contentId)
                .orElseThrow(() -> new NotFoundException("Content not found"));
        if ((content.getType() != ContentType.SERIES && content.getType() != ContentType.SEASON)
                || (content.getType() == ContentType.SEASON
                && (content.getSeasonNumber() == null || content.getSeasonNumber() <= 0))) {
            throw new BadRequestException("Long-form ratings require a series or regular season");
        }
        return content;
    }

    private Content resolveContent(MediaReference reference) {
        if (reference == null || (reference.type() != ContentType.SERIES && reference.type() != ContentType.SEASON)
                || (reference.type() == ContentType.SEASON && (reference.seasonNumber() == null
                || reference.seasonNumber() <= 0))) {
            throw new BadRequestException("Historical ratings require a series or regular season");
        }
        return switch (reference.type()) {
            case SERIES -> contentRepository.findByTmdbIdAndType(reference.tmdbId(), ContentType.SERIES)
                    .orElseThrow(() -> new NotFoundException("Content not found"));
            case SEASON -> contentRepository.findByTypeAndSeriesTmdbIdAndSeasonNumberIn(
                            ContentType.SEASON, reference.seriesTmdbId(), List.of(reference.seasonNumber())).stream()
                    .findFirst()
                    .orElseThrow(() -> new NotFoundException("Content not found"));
            default -> throw new BadRequestException("Historical ratings require a series or regular season");
        };
    }

    private void assertExpectedVersion(ContentRating rating, Long expectedVersion) {
        if (rating == null && expectedVersion != null) {
            throw new ConflictException("Rating no longer exists");
        }
        if (rating != null && (expectedVersion == null || !expectedVersion.equals(rating.getVersion()))) {
            throw new ConflictException("Rating version is stale or missing");
        }
    }

    private void requireScore(Integer score) {
        if (score == null || score < 1 || score > 10) {
            throw new BadRequestException("Score must be between 1 and 10");
        }
    }

    static boolean canSetRating(boolean exists, OpinionEligibility proof) {
        return proof.anyWatchedEvidence()
                && (proof.completeNow() || (exists && proof.previouslyEligible()));
    }
}
