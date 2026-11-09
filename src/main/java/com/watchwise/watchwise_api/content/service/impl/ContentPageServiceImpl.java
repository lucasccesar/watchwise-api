package com.watchwise.watchwise_api.content.service.impl;

import com.watchwise.watchwise_api.common.exception.NotFoundException;
import com.watchwise.watchwise_api.content.dto.ContentDetailsDTO;
import com.watchwise.watchwise_api.content.dto.ContentNavigationDTO;
import com.watchwise.watchwise_api.content.dto.ContentPageDTO;
import com.watchwise.watchwise_api.content.dto.ContentPageMetadataDTO;
import com.watchwise.watchwise_api.content.dto.ContentPageStatsDTO;
import com.watchwise.watchwise_api.content.dto.ContentPageSectionsDTO;
import com.watchwise.watchwise_api.content.dto.ContentViewerStateDTO;
import com.watchwise.watchwise_api.content.dto.EpisodeSummaryDTO;
import com.watchwise.watchwise_api.content.entity.Content;
import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.content.repository.ContentRepository;
import com.watchwise.watchwise_api.content.service.ContentCoordinate;
import com.watchwise.watchwise_api.content.service.ContentDetailsService;
import com.watchwise.watchwise_api.content.service.ContentPageDetails;
import com.watchwise.watchwise_api.content.service.ContentPageMetadataService;
import com.watchwise.watchwise_api.content.service.ContentPageService;
import com.watchwise.watchwise_api.content.service.ContentPageStatsService;
import com.watchwise.watchwise_api.content.service.ContentViewerStateService;
import com.watchwise.watchwise_api.diaryentry.repository.DiaryEntryRepository;
import com.watchwise.watchwise_api.user.entity.User;
import com.watchwise.watchwise_api.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ContentPageServiceImpl implements ContentPageService {

    private final ContentRepository contentRepository;
    private final UserRepository userRepository;
    private final DiaryEntryRepository diaryEntryRepository;
    private final ContentDetailsService contentDetailsService;
    private final ContentPageMetadataService contentPageMetadataService;
    private final ContentPageStatsService contentPageStatsService;
    private final ContentViewerStateService contentViewerStateService;
    private final ContentChildCardAssembler contentChildCardAssembler;

    @Override
    public ContentPageDTO getPage(UUID contentId, UUID viewerId) {
        Content content = contentRepository.findById(contentId)
                .orElseThrow(() -> new NotFoundException("Content not found"));
        User viewer = userRepository.findById(viewerId)
                .orElseThrow(() -> new NotFoundException("User not found"));

        ContentPageDetails pageDetails = contentDetailsService.getPageDetails(
                content, viewerId, viewer.getPreferredLanguage(), viewer.getPreferredRegion());
        boolean pageDetailsAvailable = pageDetails != null;
        if (pageDetails == null) {
            pageDetails = new ContentPageDetails(contentDetailsService.getDetails(contentId, viewerId), List.of());
        }
        ContentDetailsDTO details = pageDetails.details();
        ContentCoordinate rootCoordinate = ContentCoordinate.from(content);
        ContentViewerStateService.Resolution episodePageState = null;
        ContentPageStatsService.Resolution episodePageStats = null;
        ContentViewerStateDTO viewerState;
        if (content.getType() == ContentType.EPISODE && pageDetailsAvailable) {
            List<ContentCoordinate> pageCoordinates = episodePageCoordinates(rootCoordinate, pageDetails);
            episodePageState = contentViewerStateService.resolve(viewerId, pageCoordinates, Map.of());
            viewerState = episodePageState.statesByCoordinate().get(rootCoordinate);
            List<UUID> pageContentIds = episodePageState.existingContentIdsByCoordinate().values().stream()
                    .distinct()
                    .toList();
            episodePageStats = contentPageStatsService.resolveStats(contentId, content.getType(), pageContentIds);
        } else {
            viewerState = contentViewerStateService.resolve(viewerId, List.of(rootCoordinate), Map.of())
                    .statesByCoordinate()
                    .get(rootCoordinate);
        }

        ContentPageMetadataDTO metadata = pageDetails.metadata() == null
                ? contentPageMetadataService.getMetadata(
                        content, viewer.getPreferredLanguage(), viewer.getPreferredRegion())
                : pageDetails.metadata();
        if (content.getType() == ContentType.EPISODE) {
            String presentationPosterPath = details.posterPath() != null
                    ? details.posterPath()
                    : details.backdropPath();
            metadata = metadata.withPresentation(presentationPosterPath, details.crew(), true);
        }
        ContentPageSectionsDTO sections = content.getType() == ContentType.SEASON
                ? contentChildCardAssembler.assembleSectionsWithSeasonSummaries(
                        details,
                        pageDetails.siblingSeasons(),
                        rootCoordinate,
                        viewerId,
                        viewer.getPreferredRegion(),
                        viewer.getPreferredLanguage())
                : content.getType() == ContentType.SERIES
                        ? contentChildCardAssembler.assembleSectionsWithSeasonSummaries(
                                details,
                                details.seasons(),
                                rootCoordinate,
                                viewerId,
                                viewer.getPreferredRegion(),
                                viewer.getPreferredLanguage())
                : contentChildCardAssembler.assembleSections(details, rootCoordinate, viewerId);
        ContentNavigationDTO navigation = content.getType() == ContentType.EPISODE && pageDetailsAvailable
                ? contentChildCardAssembler.assembleNavigation(
                        pageDetails.episodeNavigation(), rootCoordinate, viewerId,
                        episodePageState, episodePageStats.statsByContentId())
                : contentChildCardAssembler.assembleNavigation(details, null, rootCoordinate, viewerId);

        ContentPageStatsDTO stats = episodePageStats == null
                ? contentPageStatsService.getStats(contentId, content.getType())
                : episodePageStats.pageStats();

        return new ContentPageDTO(
                details,
                metadata,
                stats.withTmdbVoteAverage(details.tmdbVoteAverage()),
                visibleReviewsCount(contentId, viewerId),
                viewerState,
                navigation,
                sections);
    }

    private long visibleReviewsCount(UUID contentId, UUID viewerId) {
        return diaryEntryRepository.countVisibleContentReviews(contentId, viewerId);
    }

    private List<ContentCoordinate> episodePageCoordinates(
            ContentCoordinate rootCoordinate, ContentPageDetails pageDetails) {
        List<ContentCoordinate> coordinates = new ArrayList<>(3);
        coordinates.add(rootCoordinate);
        if (pageDetails.episodeNavigation() != null) {
            addEpisodeCoordinate(coordinates, rootCoordinate.seriesTmdbId(),
                    pageDetails.episodeNavigation().previousEpisode());
            addEpisodeCoordinate(coordinates, rootCoordinate.seriesTmdbId(),
                    pageDetails.episodeNavigation().nextEpisode());
        }
        return coordinates;
    }

    private void addEpisodeCoordinate(
            List<ContentCoordinate> coordinates, String seriesTmdbId, EpisodeSummaryDTO episode) {
        if (episode == null || episode.seasonNumber() == null || episode.episodeNumber() == null) {
            return;
        }
        coordinates.add(new ContentCoordinate(
                ContentType.EPISODE, null, seriesTmdbId, episode.seasonNumber(), episode.episodeNumber()));
    }
}
