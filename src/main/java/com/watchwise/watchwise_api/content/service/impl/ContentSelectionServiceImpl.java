package com.watchwise.watchwise_api.content.service.impl;

import com.watchwise.watchwise_api.common.tmdb.TmdbClient;
import com.watchwise.watchwise_api.content.dto.ContentEpisodeSelectionDTO;
import com.watchwise.watchwise_api.content.dto.ContentSeasonSelectionDTO;
import com.watchwise.watchwise_api.content.service.ContentSelectionService;
import com.watchwise.watchwise_api.content.service.TmdbSeriesHierarchyService;
import com.watchwise.watchwise_api.user.repository.UserRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Service
public class ContentSelectionServiceImpl implements ContentSelectionService {
    private final UserRepository userRepository;
    private final TmdbSeriesHierarchyService hierarchyService;

    public ContentSelectionServiceImpl(UserRepository userRepository, TmdbSeriesHierarchyService hierarchyService) {
        this.userRepository = userRepository;
        this.hierarchyService = hierarchyService;
    }

    @Override
    public List<ContentSeasonSelectionDTO> listSeasons(UUID viewerId, String seriesTmdbId, boolean includeSpecials) {
        return hierarchyService.listSeasons(seriesTmdbId, includeSpecials, languageFor(viewerId));
    }

    @Override
    public List<ContentEpisodeSelectionDTO> listEpisodes(
            UUID viewerId, String seriesTmdbId, int seasonNumber, boolean includeUnreleased) {
        return hierarchyService.listEpisodes(seriesTmdbId, seasonNumber, includeUnreleased, languageFor(viewerId));
    }

    private String languageFor(UUID viewerId) {
        return userRepository.findById(viewerId)
                .map(user -> user.getPreferredLanguage())
                .filter(language -> language != null && !language.isBlank())
                .orElse(TmdbClient.LANGUAGE_INDEPENDENT_LOOKUP_LANGUAGE);
    }
}
