package com.watchwise.watchwise_api.person.service.impl;

import com.watchwise.watchwise_api.common.tmdb.TmdbClient;
import com.watchwise.watchwise_api.common.tmdb.TmdbLookupResult;
import com.watchwise.watchwise_api.common.tmdb.TmdbPersonDetails;
import com.watchwise.watchwise_api.content.dto.ContentPreviewStatus;
import com.watchwise.watchwise_api.person.dto.PersonPreviewDTO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class TmdbPersonPreviewResolverTest {

    private final TmdbClient tmdbClient = mock(TmdbClient.class);
    private final TmdbPersonPreviewResolver resolver = new TmdbPersonPreviewResolver(tmdbClient, Runnable::run);

    @Test
    @DisplayName("[resolve] Should Drop Blanks, Deduplicate IDs And Preserve Preview Statuses - When IDs Are Mixed")
    void shouldDropBlanksDeduplicateIdsAndPreservePreviewStatusesWhenIdsAreMixed() {
        when(tmdbClient.getPersonDetails("42"))
                .thenReturn(new TmdbLookupResult.Found<>(new TmdbPersonDetails("42", "Ada", "/ada.jpg", null)));
        when(tmdbClient.getPersonDetails("77"))
                .thenReturn(new TmdbLookupResult.Found<>(new TmdbPersonDetails("77", "Grace", null, null)));
        when(tmdbClient.getPersonDetails("99")).thenReturn(new TmdbLookupResult.NotFound<>());
        when(tmdbClient.getPersonDetails("100")).thenReturn(new TmdbLookupResult.Unavailable<>());

        Map<String, PersonPreviewDTO> previews = resolver.resolve(
                Arrays.asList(null, "  ", "42", "77", "42", "99", "", "100"));

        assertThat(previews).containsExactly(
                Map.entry("42", new PersonPreviewDTO(
                        "42", "Ada", "https://image.tmdb.org/t/p/w185/ada.jpg", ContentPreviewStatus.AVAILABLE)),
                Map.entry("77", new PersonPreviewDTO("77", "Grace", null, ContentPreviewStatus.PARTIAL)),
                Map.entry("99", new PersonPreviewDTO("99", null, null, ContentPreviewStatus.UNAVAILABLE)),
                Map.entry("100", new PersonPreviewDTO("100", null, null, ContentPreviewStatus.UNAVAILABLE)));
        verify(tmdbClient).getPersonDetails("42");
        verify(tmdbClient).getPersonDetails("77");
        verify(tmdbClient).getPersonDetails("99");
        verify(tmdbClient).getPersonDetails("100");
        verifyNoMoreInteractions(tmdbClient);
    }

    @Test
    @DisplayName("[resolve] Should Keep The ID As Unavailable - When A Lookup Throws")
    void shouldKeepTheIdAsUnavailableWhenLookupThrows() {
        when(tmdbClient.getPersonDetails("42")).thenThrow(new IllegalStateException("TMDB unavailable"));

        Map<String, PersonPreviewDTO> previews = resolver.resolve(List.of("42"));

        assertThat(previews).containsEntry("42",
                new PersonPreviewDTO("42", null, null, ContentPreviewStatus.UNAVAILABLE));
    }
}
