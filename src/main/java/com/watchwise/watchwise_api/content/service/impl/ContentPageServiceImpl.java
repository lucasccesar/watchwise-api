package com.watchwise.watchwise_api.content.service.impl;

import com.watchwise.watchwise_api.common.exception.NotFoundException;
import com.watchwise.watchwise_api.content.dto.ContentDetailsDTO;
import com.watchwise.watchwise_api.content.dto.ContentNavigationDTO;
import com.watchwise.watchwise_api.content.dto.ContentPageDTO;
import com.watchwise.watchwise_api.content.dto.ContentPageMetadataDTO;
import com.watchwise.watchwise_api.content.dto.ContentPageSectionsDTO;
import com.watchwise.watchwise_api.content.dto.ContentViewerStateDTO;
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
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

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

        ContentPageDetails pageDetails = (content.getType() == ContentType.SEASON
                        || content.getType() == ContentType.EPISODE)
                ? contentDetailsService.getPageDetails(
                        contentId, viewerId, viewer.getPreferredLanguage(), viewer.getPreferredRegion())
                : new ContentPageDetails(contentDetailsService.getDetails(contentId, viewerId), List.of());
        boolean pageDetailsAvailable = pageDetails != null;
        if (pageDetails == null) {
            pageDetails = new ContentPageDetails(contentDetailsService.getDetails(contentId, viewerId), List.of());
        }
        ContentDetailsDTO details = pageDetails.details();
        ContentCoordinate rootCoordinate = ContentCoordinate.from(content);
        ContentViewerStateDTO viewerState = contentViewerStateService.resolve(
                        viewerId, List.of(rootCoordinate), Map.of())
                .statesByCoordinate()
                .get(rootCoordinate);

        ContentDetailsDTO parentSeasonDetails = parentSeasonDetails(content, viewerId);
        ContentPageMetadataDTO metadata = contentPageMetadataService.getMetadata(
                content, viewer.getPreferredLanguage(), viewer.getPreferredRegion());
        if (content.getType() == ContentType.EPISODE) {
            String presentationPosterPath = parentSeasonDetails != null
                    && parentSeasonDetails.posterPath() != null
                    ? parentSeasonDetails.posterPath()
                    : details.posterPath();
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
                        pageDetails.episodeNavigation(), rootCoordinate, viewerId)
                : contentChildCardAssembler.assembleNavigation(details, parentSeasonDetails, rootCoordinate, viewerId);

        return new ContentPageDTO(
                details,
                metadata,
                contentPageStatsService.getStats(contentId).withTmdbVoteAverage(details.tmdbVoteAverage()),
                visibleReviewsCount(contentId, viewerId),
                viewerState,
                navigation,
                sections);
    }

    private ContentDetailsDTO parentSeasonDetails(Content content, UUID viewerId) {
        if (content.getType() != ContentType.EPISODE) {
            return null;
        }
        return contentRepository.findBySeriesTmdbIdAndSeasonNumberAndEpisodeNumberAndType(
                        content.getSeriesTmdbId(), content.getSeasonNumber(), null, ContentType.SEASON)
                .map(parent -> contentDetailsService.getDetails(parent.getId(), viewerId))
                .orElse(null);
    }

    private long visibleReviewsCount(UUID contentId, UUID viewerId) {
        return diaryEntryRepository.findContentReviewKeys(
                        contentId, viewerId, PageRequest.of(0, 1))
                .getTotalElements();
    }
}
