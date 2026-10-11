package com.watchwise.watchwise_api.content.service.impl;

import com.watchwise.watchwise_api.common.tmdb.TmdbClient;
import com.watchwise.watchwise_api.content.dto.ContentEpisodeSelectionDTO;
import com.watchwise.watchwise_api.content.service.ContentSelectionService;
import com.watchwise.watchwise_api.content.service.TmdbSeriesHierarchyService;
import com.watchwise.watchwise_api.user.entity.User;
import com.watchwise.watchwise_api.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ContentSelectionServiceImplTest {
    @Mock private UserRepository userRepository;
    @Mock private TmdbSeriesHierarchyService hierarchyService;

    @Test
    void delegatesUsingViewerLanguageAndIndependentFallback() {
        UUID viewerId = UUID.randomUUID();
        when(userRepository.findById(viewerId)).thenReturn(Optional.of(User.builder().preferredLanguage("pt-BR").build()));
        when(hierarchyService.listSeasons("1399", false, "pt-BR")).thenReturn(List.of());
        assertThat(service().listSeasons(viewerId, "1399", false)).isEmpty();
        verify(hierarchyService).listSeasons("1399", false, "pt-BR");
    }

    @Test
    void delegatesEpisodeOptionsAndUsesFallbackWhenViewerMissing() {
        UUID viewerId = UUID.randomUUID();
        when(userRepository.findById(viewerId)).thenReturn(Optional.empty());
        when(hierarchyService.listEpisodes("1399", 0, true, TmdbClient.LANGUAGE_INDEPENDENT_LOOKUP_LANGUAGE))
                .thenReturn(List.<ContentEpisodeSelectionDTO>of());

        assertThat(service().listEpisodes(viewerId, "1399", 0, true)).isEmpty();
        verify(hierarchyService).listEpisodes("1399", 0, true, TmdbClient.LANGUAGE_INDEPENDENT_LOOKUP_LANGUAGE);
    }

    private ContentSelectionService service() {
        return new ContentSelectionServiceImpl(userRepository, hierarchyService);
    }
}
