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
import com.watchwise.watchwise_api.contentrating.dto.ContentRatingStateDTO;
import com.watchwise.watchwise_api.contentrating.entity.ContentRating;
import com.watchwise.watchwise_api.contentrating.entity.ContentRatingPass;
import com.watchwise.watchwise_api.contentrating.repository.ContentRatingRepository;
import com.watchwise.watchwise_api.contentrating.repository.ContentRatingPassRepository;
import com.watchwise.watchwise_api.contentrating.service.ContentRatingService;
import com.watchwise.watchwise_api.contentrating.service.OpinionEligibility;
import com.watchwise.watchwise_api.contentrating.service.OpinionEvidenceService;
import com.watchwise.watchwise_api.contentreview.service.HistoricalViewingEvidence;
import com.watchwise.watchwise_api.platformimport.model.ConfirmedWatchScopeSnapshot;
import com.watchwise.watchwise_api.contentreview.service.HistoricalViewingEvidenceVerifier;
import org.springframework.dao.DataIntegrityViolationException;
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
    private final ContentRatingPassRepository passRatingRepository;
    private final ContentRepository contentRepository;
    private final OpinionEvidenceService opinionEvidenceService;
    private final HistoricalViewingEvidenceVerifier historicalEvidenceVerifier;
    private final UserContentMutationLock userContentMutationLock;

    public ContentRatingServiceImpl(
            ContentRatingRepository ratingRepository,
            ContentRatingPassRepository passRatingRepository,
            ContentRepository contentRepository,
            OpinionEvidenceService opinionEvidenceService,
            HistoricalViewingEvidenceVerifier historicalEvidenceVerifier,
            UserContentMutationLock userContentMutationLock) {
        this.ratingRepository = ratingRepository;
        this.passRatingRepository = passRatingRepository;
        this.contentRepository = contentRepository;
        this.opinionEvidenceService = opinionEvidenceService;
        this.historicalEvidenceVerifier = historicalEvidenceVerifier;
        this.userContentMutationLock = userContentMutationLock;
    }

    @Override
    @Transactional(readOnly = true)
    public ContentRatingStateDTO getCurrent(UUID ownerId, UUID contentId) {
        requireOpinionContent(contentId);
        ContentRating rating = ratingRepository.findByUserIdAndContentId(ownerId, contentId).orElse(null);
        return rating == null
                ? new ContentRatingStateDTO(null, null)
                : new ContentRatingStateDTO(rating.getScore(), rating.getVersion());
    }

    @Override
    @Transactional
    public ContentRatingResponseDTO set(UUID ownerId, UUID contentId, Integer score, Long expectedVersion) {
        requireScore(score);
        Content content = requireOpinionContent(contentId);
        Instant now = Instant.now();
        OpinionEvidenceService.Snapshot evidence = opinionEvidenceService.capture(
                ownerId, content, true, now);

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
        Integer targetWatchNumber = evidence.completedWatchNumber();
        if (targetWatchNumber == null && rating != null) {
            targetWatchNumber = rating.getWatchNumber();
        }
        if (targetWatchNumber == null && rating == null) {
            throw new BadRequestException("A new rating requires a completed pass number");
        }
        if (targetWatchNumber != null) {
            requirePositiveWatchNumber(targetWatchNumber);
        }
        ContentRatingPass pass = findPassRating(ownerId, contentId, targetWatchNumber);
        PassRatingWriteResult write = writePassRating(
                ownerId, content, score, targetWatchNumber, rating, pass, updatedAt);
        return new ContentRatingResponseDTO(write.currentRating().getScore(), write.currentRating().getVersion());
    }

    @Override
    @Transactional
    public ContentRatingResponseDTO setForPass(
            UUID ownerId,
            UUID contentId,
            Integer score,
            Integer watchNumber,
            Long expectedVersion,
            Instant updatedAt) {
        requireScore(score);
        requirePositiveWatchNumber(watchNumber);
        Content content = requireOpinionContent(contentId);
        userContentMutationLock.lock(ownerId);
        ContentRating rating = ratingRepository.findByUserIdAndContentId(ownerId, contentId).orElse(null);
        assertExpectedVersion(rating, expectedVersion);
        ContentRatingPass pass = findPassRating(ownerId, contentId, watchNumber);
        PassRatingWriteResult write = writePassRating(
                ownerId, content, score, watchNumber, rating, pass, updatedAt);
        return new ContentRatingResponseDTO(write.currentRating().getScore(), write.currentRating().getVersion());
    }

    private PassRatingWriteResult writePassRating(
            UUID ownerId,
            Content content,
            Integer score,
            Integer watchNumber,
            ContentRating rating,
            ContentRatingPass pass,
            Instant updatedAt) {
        if (pass == null) {
            pass = ContentRatingPass.builder()
                    .userId(ownerId)
                    .contentId(content.getId())
                    .contentType(content.getType())
                    .watchNumber(watchNumber)
                    .score(score)
                    .createdAt(updatedAt)
                    .updatedAt(updatedAt)
                    .build();
        } else {
            pass.setScore(score);
            pass.setUpdatedAt(updatedAt);
        }

        try {
            pass = passRatingRepository.saveAndFlush(pass);
            boolean updatesCurrentProjection = rating == null
                    || (watchNumber == null
                    ? rating.getWatchNumber() == null
                    : rating.getWatchNumber() == null || watchNumber >= rating.getWatchNumber());
            if (updatesCurrentProjection) {
                if (rating == null) {
                    rating = ContentRating.builder()
                        .userId(ownerId)
                        .contentId(content.getId())
                        .contentType(content.getType())
                        .score(score)
                        .watchNumber(watchNumber)
                        .createdAt(updatedAt)
                        .updatedAt(updatedAt)
                        .build();
                } else {
                    rating.setScore(score);
                    rating.setWatchNumber(watchNumber);
                    rating.setUpdatedAt(updatedAt);
                }
                rating = ratingRepository.saveAndFlush(rating);
            }
        } catch (DataIntegrityViolationException conflict) {
            throw new ConflictException("Rating pass changed while it was being updated");
        } catch (OptimisticLockingFailureException conflict) {
            throw new ConflictException("Rating changed while it was being updated");
        }
        return new PassRatingWriteResult(rating, pass);
    }

    private ContentRatingPass findPassRating(UUID ownerId, UUID contentId, Integer watchNumber) {
        return watchNumber == null
                ? passRatingRepository.findFirstByUserIdAndContentIdAndWatchNumberIsNull(ownerId, contentId)
                        .orElse(null)
                : passRatingRepository.findByUserIdAndContentIdAndWatchNumber(ownerId, contentId, watchNumber)
                        .orElse(null);
    }

    private record PassRatingWriteResult(ContentRating currentRating, ContentRatingPass passRating) { }

    @Override
    @Transactional
    public void remove(UUID ownerId, UUID contentId) {
        requireOpinionContent(contentId);
        userContentMutationLock.lock(ownerId);
        ContentRating rating = ratingRepository.findByUserIdAndContentId(ownerId, contentId).orElse(null);
        if (rating == null) {
            return;
        }

        Integer currentWatchNumber = rating.getWatchNumber();
        if (currentWatchNumber == null) {
            passRatingRepository.findFirstByUserIdAndContentIdAndWatchNumberIsNull(ownerId, contentId)
                    .ifPresent(passRatingRepository::delete);
        } else {
            passRatingRepository.findByUserIdAndContentIdAndWatchNumber(ownerId, contentId, currentWatchNumber)
                    .ifPresent(passRatingRepository::delete);
        }

        Optional<ContentRatingPass> fallback = currentWatchNumber == null
                ? Optional.empty()
                : passRatingRepository.findFirstByUserIdAndContentIdAndWatchNumberLessThanOrderByWatchNumberDesc(
                        ownerId, contentId, currentWatchNumber);
        if (fallback.isEmpty()) {
            fallback = passRatingRepository.findFirstByUserIdAndContentIdAndWatchNumberIsNull(ownerId, contentId);
        }
        if (fallback.isEmpty()) {
            ratingRepository.delete(rating);
            return;
        }

        ContentRatingPass previous = fallback.get();
        rating.setScore(previous.getScore());
        rating.setWatchNumber(previous.getWatchNumber());
        rating.setUpdatedAt(Instant.now());
        try {
            ratingRepository.saveAndFlush(rating);
        } catch (OptimisticLockingFailureException conflict) {
            throw new ConflictException("Rating changed while it was being removed");
        }
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
        ContentRatingPass pass = findPassRating(ownerId, content.getId(), evidence.watchNumber());
        if (pass != null) {
            return Optional.empty();
        }
        ContentRating rating = ratingRepository.findByUserIdAndContentId(ownerId, content.getId()).orElse(null);
        historicalEvidenceVerifier.revalidate(ownerId, content, evidence, verified);
        PassRatingWriteResult write = writePassRating(
                ownerId, content, score, evidence.watchNumber(), rating, null, now);
        return Optional.of(write.passRating().getId());
    }

    @Override
    @Transactional
    public Optional<UUID> restoreInitial(
            UUID ownerId,
            MediaReference reference,
            Integer score,
            HistoricalViewingEvidence evidence,
            ConfirmedWatchScopeSnapshot preparedScope) {
        requireScore(score);
        if (evidence == null || preparedScope == null) {
            throw new BadRequestException("Prepared historical viewing evidence is required");
        }
        Content content = resolveContent(reference);
        Instant now = Instant.now();
        HistoricalViewingEvidenceVerifier.Verification verified = historicalEvidenceVerifier.verifyPreparedScope(
                ownerId, content, evidence, preparedScope, true, now);

        userContentMutationLock.lock(ownerId);
        ContentRatingPass pass = findPassRating(ownerId, content.getId(), evidence.watchNumber());
        if (pass != null) {
            return Optional.empty();
        }
        ContentRating rating = ratingRepository.findByUserIdAndContentId(ownerId, content.getId()).orElse(null);
        historicalEvidenceVerifier.revalidatePreparedScope(ownerId, content, evidence, preparedScope, verified);
        PassRatingWriteResult write = writePassRating(
                ownerId, content, score, evidence.watchNumber(), rating, null, now);
        return Optional.of(write.passRating().getId());
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

    private void requirePositiveWatchNumber(Integer watchNumber) {
        if (watchNumber == null || watchNumber <= 0) {
            throw new BadRequestException("A rating pass number must be positive");
        }
    }

    static boolean canSetRating(boolean exists, OpinionEligibility proof) {
        return proof.anyWatchedEvidence()
                && (proof.completeNow() || (exists && proof.previouslyEligible()));
    }
}
