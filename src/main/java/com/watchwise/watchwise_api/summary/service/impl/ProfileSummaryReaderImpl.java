package com.watchwise.watchwise_api.summary.service.impl;

import com.watchwise.watchwise_api.common.exception.BadRequestException;
import com.watchwise.watchwise_api.common.exception.ForbiddenException;
import com.watchwise.watchwise_api.common.exception.NotFoundException;
import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.follower.entity.FollowStatus;
import com.watchwise.watchwise_api.follower.repository.FollowerRepository;
import com.watchwise.watchwise_api.summary.dto.ProfileHighlightsDTO;
import com.watchwise.watchwise_api.summary.dto.ProfileLongestWatchDTO;
import com.watchwise.watchwise_api.summary.dto.ProfileRewatchHighlightDTO;
import com.watchwise.watchwise_api.summary.dto.RatingCountDTO;
import com.watchwise.watchwise_api.summary.dto.RatingsSummaryDTO;
import com.watchwise.watchwise_api.summary.dto.SummaryResponseDTO;
import com.watchwise.watchwise_api.summary.service.ProfileDisplayMetadataResolver;
import com.watchwise.watchwise_api.summary.service.ProfileSummaryDataReader;
import com.watchwise.watchwise_api.summary.service.ProfileSummaryReader;
import com.watchwise.watchwise_api.user.entity.User;
import com.watchwise.watchwise_api.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ProfileSummaryReaderImpl implements ProfileSummaryReader {

    private static final Set<ContentType> ALLOWED_TYPES = Set.of(ContentType.MOVIE, ContentType.SERIES);

    private final UserRepository userRepository;
    private final FollowerRepository followerRepository;
    private final ProfileSummaryDataReader dataReader;
    private final ProfileDisplayMetadataResolver displayMetadataResolver;

    @Override
    public SummaryResponseDTO read(UUID viewerId, UUID userId, ContentType type) {
        User target = userRepository.findById(userId)
                .orElseThrow(() -> new NotFoundException("User not found"));
        assertCanView(viewerId, userId, target);
        validateType(type);

        ProfileSummaryDataReader.Snapshot snapshot = dataReader.read(userId, type);
        ProfileHighlightsDTO highlights = resolveHighlights(userId, snapshot);
        RatingsSummaryDTO ratingsSummary = buildRatingsSummary(snapshot.ratingsDistribution());

        return new SummaryResponseDTO(snapshot.watchTime(), highlights, snapshot.genreCounts(), ratingsSummary,
                snapshot.ratingsDistribution(), snapshot.recentEpisodes(), snapshot.recentReviews(),
                snapshot.recentActivity());
    }

    private void validateType(ContentType type) {
        if (type == null || !ALLOWED_TYPES.contains(type)) {
            throw new BadRequestException("type must be one of: MOVIE, SERIES");
        }
    }

    private void assertCanView(UUID viewerId, UUID targetUserId, User target) {
        if (Boolean.TRUE.equals(target.getIsProfilePublic()) || viewerId.equals(targetUserId)) {
            return;
        }

        if (!followerRepository.existsByFollowerIdAndFollowedIdAndStatus(
                viewerId, targetUserId, FollowStatus.ACCEPTED)) {
            throw new ForbiddenException("This user profile is private");
        }
    }

    private ProfileHighlightsDTO resolveHighlights(UUID ownerId, ProfileSummaryDataReader.Snapshot snapshot) {
        ProfileRewatchHighlightDTO rewatch = null;
        if (snapshot.rewatch() != null) {
            var content = displayMetadataResolver.resolveStoredContent(ownerId, snapshot.rewatch().content());
            if (content != null) {
                rewatch = new ProfileRewatchHighlightDTO(
                        snapshot.rewatch().kind(), content, snapshot.rewatch().count());
            }
        }

        ProfileLongestWatchDTO longestWatch = null;
        if (snapshot.longestWatch() != null) {
            var content = snapshot.longestWatch().content() != null
                    ? displayMetadataResolver.resolveStoredContent(ownerId, snapshot.longestWatch().content())
                    : displayMetadataResolver.resolveSeries(ownerId, snapshot.longestWatch().seriesTmdbId());
            if (content != null) {
                longestWatch = new ProfileLongestWatchDTO(
                        content, snapshot.longestWatch().totalMinutesWatched(),
                        snapshot.longestWatch().watchedEpisodeCount());
            }
        }
        return new ProfileHighlightsDTO(rewatch, longestWatch);
    }

    private RatingsSummaryDTO buildRatingsSummary(List<RatingCountDTO> distribution) {
        long totalRatings = distribution.stream().mapToLong(RatingCountDTO::count).sum();
        if (totalRatings == 0) {
            return new RatingsSummaryDTO(0L, null);
        }
        double average = distribution.stream()
                .mapToDouble(row -> row.score() * (double) row.count())
                .sum() / totalRatings;
        return new RatingsSummaryDTO(totalRatings, average);
    }
}
