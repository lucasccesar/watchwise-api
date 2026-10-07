package com.watchwise.watchwise_api.content.service.impl;

import com.watchwise.watchwise_api.common.exception.NotFoundException;
import com.watchwise.watchwise_api.content.dto.ContentDetailsDTO;
import com.watchwise.watchwise_api.content.dto.ContentNavigationDTO;
import com.watchwise.watchwise_api.content.dto.ContentPageDTO;
import com.watchwise.watchwise_api.content.dto.ContentPageSectionsDTO;
import com.watchwise.watchwise_api.content.dto.ContentViewerStateDTO;
import com.watchwise.watchwise_api.content.entity.Content;
import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.content.repository.ContentRepository;
import com.watchwise.watchwise_api.content.service.ContentCoordinate;
import com.watchwise.watchwise_api.content.service.ContentDetailsService;
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

        ContentDetailsDTO details = contentDetailsService.getDetails(contentId, viewerId);
        ContentCoordinate rootCoordinate = ContentCoordinate.from(content);
        ContentViewerStateDTO viewerState = contentViewerStateService.resolve(
                        viewerId, List.of(rootCoordinate), Map.of())
                .statesByCoordinate()
                .get(rootCoordinate);

        ContentDetailsDTO parentSeriesDetails = parentSeriesDetails(content, viewerId);
        ContentDetailsDTO parentSeasonDetails = parentSeasonDetails(content, viewerId);
        ContentPageSectionsDTO sections = contentChildCardAssembler.assembleSections(
                details, parentSeriesDetails, rootCoordinate, viewerId);
        ContentNavigationDTO navigation = contentChildCardAssembler.assembleNavigation(
                details, parentSeasonDetails, rootCoordinate, viewerId);

        return new ContentPageDTO(
                details,
                contentPageMetadataService.getMetadata(
                        content, viewer.getPreferredLanguage(), viewer.getPreferredRegion()),
                contentPageStatsService.getStats(contentId),
                visibleReviewsCount(contentId, viewerId),
                viewerState,
                navigation,
                sections);
    }

    private ContentDetailsDTO parentSeriesDetails(Content content, UUID viewerId) {
        if (content.getType() != ContentType.SEASON) {
            return null;
        }
        return contentRepository.findByTmdbIdAndType(content.getSeriesTmdbId(), ContentType.SERIES)
                .map(parent -> contentDetailsService.getDetails(parent.getId(), viewerId))
                .orElse(null);
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
