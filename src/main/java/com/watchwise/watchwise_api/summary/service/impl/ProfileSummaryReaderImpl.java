package com.watchwise.watchwise_api.summary.service.impl;

import com.watchwise.watchwise_api.common.exception.BadRequestException;
import com.watchwise.watchwise_api.common.exception.ForbiddenException;
import com.watchwise.watchwise_api.common.exception.NotFoundException;
import com.watchwise.watchwise_api.content.dto.ContentCardDTO;
import com.watchwise.watchwise_api.content.dto.ContentCardFieldSet;
import com.watchwise.watchwise_api.content.dto.ContentRefDTO;
import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.content.service.ContentCardContext;
import com.watchwise.watchwise_api.content.service.ContentCardSpec;
import com.watchwise.watchwise_api.content.service.ContentCoordinate;
import com.watchwise.watchwise_api.content.service.impl.ContentCardAssembler;
import com.watchwise.watchwise_api.follower.entity.FollowStatus;
import com.watchwise.watchwise_api.follower.repository.FollowerRepository;
import com.watchwise.watchwise_api.summary.dto.ProfileHighlightsDTO;
import com.watchwise.watchwise_api.summary.dto.ProfileDiaryPreviewDTO;
import com.watchwise.watchwise_api.summary.dto.ProfileLongestWatchDTO;
import com.watchwise.watchwise_api.summary.dto.ProfileRewatchHighlightDTO;
import com.watchwise.watchwise_api.summary.dto.RatingCountDTO;
import com.watchwise.watchwise_api.summary.dto.RatingsSummaryDTO;
import com.watchwise.watchwise_api.summary.dto.RecentActivityItemDTO;
import com.watchwise.watchwise_api.summary.dto.SummaryResponseDTO;
import com.watchwise.watchwise_api.summary.service.ProfileDisplayMetadataResolver;
import com.watchwise.watchwise_api.summary.service.ProfileSummaryDataReader;
import com.watchwise.watchwise_api.summary.service.ProfileSummaryReader;
import com.watchwise.watchwise_api.user.entity.User;
import com.watchwise.watchwise_api.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

@Service
@RequiredArgsConstructor
public class ProfileSummaryReaderImpl implements ProfileSummaryReader {

    private static final Set<ContentType> ALLOWED_TYPES = Set.of(ContentType.MOVIE, ContentType.SERIES);
    private static final Set<ContentCardFieldSet> PROFILE_CARD_FIELDS = Set.of(
            ContentCardFieldSet.BASIC_METADATA, ContentCardFieldSet.SOCIAL_METADATA);

    private final UserRepository userRepository;
    private final FollowerRepository followerRepository;
    private final ProfileSummaryDataReader dataReader;
    private final ProfileDisplayMetadataResolver displayMetadataResolver;
    private final ContentCardAssembler contentCardAssembler;

    @Override
    public SummaryResponseDTO read(UUID viewerId, UUID userId, ContentType type) {
        User target = userRepository.findById(userId)
                .orElseThrow(() -> new NotFoundException("User not found"));
        assertCanView(viewerId, userId, target);
        validateType(type);

        ProfileSummaryDataReader.Snapshot snapshot = dataReader.read(userId, type);
        snapshot = enrichVisualCards(userId, target, snapshot);
        ProfileHighlightsDTO highlights = resolveHighlights(userId, snapshot);
        RatingsSummaryDTO ratingsSummary = buildRatingsSummary(snapshot.ratingsDistribution());

        return new SummaryResponseDTO(snapshot.watchTime(), highlights, snapshot.genreCounts(), ratingsSummary,
                snapshot.ratingsDistribution(), snapshot.recentEpisodes(), snapshot.recentReviews(),
                snapshot.recentActivity());
    }

    private ProfileSummaryDataReader.Snapshot enrichVisualCards(
            UUID ownerId, User owner, ProfileSummaryDataReader.Snapshot snapshot) {
        List<ContentRefDTO> contentRefs = Stream.of(
                        snapshot.recentEpisodes().stream().map(ProfileDiaryPreviewDTO::content),
                        snapshot.recentReviews().stream().map(ProfileDiaryPreviewDTO::content),
                        snapshot.recentActivity().stream().map(item -> item.content()))
                .flatMap(stream -> stream)
                .filter(content -> content != null && content.type() != null)
                .toList();
        if (contentRefs.isEmpty()) {
            return snapshot;
        }

        List<ContentCardSpec> specs = contentRefs.stream()
                .map(content -> new ContentCardSpec(toCoordinate(content), null, null, null, null))
                .toList();
        Map<ContentCoordinate, ContentCardDTO> cards = contentCardAssembler.assemble(
                specs,
                new ContentCardContext(owner.getPreferredLanguage(), owner.getPreferredRegion(), ownerId, null),
                PROFILE_CARD_FIELDS);
        cards = cards == null ? Map.of() : cards;

        return new ProfileSummaryDataReader.Snapshot(
                snapshot.watchTime(), snapshot.genreCounts(), snapshot.ratingsDistribution(),
                addCardsToPreviews(snapshot.recentEpisodes(), cards),
                addCardsToPreviews(snapshot.recentReviews(), cards),
                addCardsToActivity(snapshot.recentActivity(), cards),
                snapshot.rewatch(), snapshot.longestWatch());
    }

    private List<ProfileDiaryPreviewDTO> addCardsToPreviews(
            List<ProfileDiaryPreviewDTO> previews, Map<ContentCoordinate, ContentCardDTO> cards) {
        return previews.stream()
                .map(preview -> {
                    ContentCardDTO card = cardFor(preview.content(), cards);
                    return new ProfileDiaryPreviewDTO(preview.id(), preview.content(), preview.score(),
                            preview.watchedDate(), preview.watchNumber(),
                            card == null ? preview.customPosterUrl() : card.customPosterUrl(),
                            preview.watchedWith(), card);
                })
                .toList();
    }

    private List<RecentActivityItemDTO> addCardsToActivity(
            List<RecentActivityItemDTO> activity, Map<ContentCoordinate, ContentCardDTO> cards) {
        return activity.stream()
                .map(item -> new RecentActivityItemDTO(item.content(), item.status(), item.score(),
                        item.timesWatched(), item.comment(), item.activityDate(), cardFor(item.content(), cards)))
                .toList();
    }

    private ContentCardDTO cardFor(ContentRefDTO content, Map<ContentCoordinate, ContentCardDTO> cards) {
        return content == null || content.type() == null ? null : cards.get(toCoordinate(content));
    }

    private ContentCoordinate toCoordinate(ContentRefDTO content) {
        return new ContentCoordinate(content.type(), content.tmdbId(), content.seriesTmdbId(),
                content.seasonNumber(), content.episodeNumber());
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
