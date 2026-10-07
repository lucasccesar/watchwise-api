package com.watchwise.watchwise_api.content.service.impl;

import com.watchwise.watchwise_api.content.dto.ContentDetailsDTO;
import com.watchwise.watchwise_api.content.entity.Content;
import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.content.repository.ContentRepository;
import com.watchwise.watchwise_api.content.service.ContentDetailsService;
import com.watchwise.watchwise_api.content.service.ContentPageParentDetailsService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ContentPageParentDetailsServiceImpl implements ContentPageParentDetailsService {

    private final ContentRepository contentRepository;
    private final ContentDetailsService contentDetailsService;

    @Override
    public ContentDetailsDTO resolveSeries(Content child, UUID viewerId) {
        if (child == null || child.getType() != ContentType.SEASON || !hasText(child.getSeriesTmdbId())) {
            return null;
        }
        return contentRepository.findByTmdbIdAndType(child.getSeriesTmdbId(), ContentType.SERIES)
                .map(parent -> contentDetailsService.getDetails(parent.getId(), viewerId))
                .orElse(null);
    }

    @Override
    public ContentDetailsDTO resolveSeason(Content child, UUID viewerId) {
        if (child == null
                || child.getType() != ContentType.EPISODE
                || !hasText(child.getSeriesTmdbId())
                || child.getSeasonNumber() == null) {
            return null;
        }
        return contentRepository.findBySeriesTmdbIdAndSeasonNumberAndEpisodeNumberAndType(
                        child.getSeriesTmdbId(), child.getSeasonNumber(), null, ContentType.SEASON)
                .map(parent -> contentDetailsService.getDetails(parent.getId(), viewerId))
                .orElse(null);

    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
