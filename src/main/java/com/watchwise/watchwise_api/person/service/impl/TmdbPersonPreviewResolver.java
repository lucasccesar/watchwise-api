package com.watchwise.watchwise_api.person.service.impl;

import com.watchwise.watchwise_api.common.tmdb.TmdbClient;
import com.watchwise.watchwise_api.common.tmdb.TmdbLookupResult;
import com.watchwise.watchwise_api.common.tmdb.TmdbImageUrlBuilder;
import com.watchwise.watchwise_api.common.tmdb.TmdbPersonDetails;
import com.watchwise.watchwise_api.content.dto.ContentPreviewStatus;
import com.watchwise.watchwise_api.person.dto.PersonPreviewDTO;
import com.watchwise.watchwise_api.person.service.PersonPreviewResolver;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

@Service
public final class TmdbPersonPreviewResolver implements PersonPreviewResolver {

    private final TmdbClient tmdbClient;
    private final Executor metadataResolutionExecutor;

    @Autowired
    public TmdbPersonPreviewResolver(
            TmdbClient tmdbClient,
            @Qualifier("contentCardMetadataResolutionExecutor") Executor metadataResolutionExecutor) {
        this.tmdbClient = tmdbClient;
        this.metadataResolutionExecutor = metadataResolutionExecutor;
    }

    @Override
    public Map<String, PersonPreviewDTO> resolve(Collection<String> personTmdbIds) {
        List<String> distinctIds = distinctIds(personTmdbIds);
        if (distinctIds.isEmpty()) {
            return Map.of();
        }

        Map<String, CompletableFuture<PersonPreviewDTO>> futures = new LinkedHashMap<>();
        distinctIds.forEach(personTmdbId -> futures.put(personTmdbId,
                CompletableFuture.supplyAsync(() -> resolveOne(personTmdbId), metadataResolutionExecutor)));

        Map<String, PersonPreviewDTO> previews = new LinkedHashMap<>();
        futures.forEach((personTmdbId, future) -> previews.put(personTmdbId, future.join()));
        return Collections.unmodifiableMap(previews);
    }

    private List<String> distinctIds(Collection<String> personTmdbIds) {
        if (personTmdbIds == null || personTmdbIds.isEmpty()) {
            return List.of();
        }
        return personTmdbIds.stream()
                .filter(Objects::nonNull)
                .filter(personTmdbId -> !personTmdbId.isBlank())
                .distinct()
                .toList();
    }

    private PersonPreviewDTO resolveOne(String personTmdbId) {
        try {
            TmdbLookupResult<TmdbPersonDetails> lookup = tmdbClient.getPersonDetails(personTmdbId);
            if (lookup instanceof TmdbLookupResult.Found<TmdbPersonDetails> found) {
                return toPreview(personTmdbId, found.value());
            }
        } catch (RuntimeException ignored) {
            // A failed external lookup is unavailable preview data, not a page failure.
        }
        return unavailable(personTmdbId);
    }

    private PersonPreviewDTO toPreview(String personTmdbId, TmdbPersonDetails details) {
        if (details == null) {
            return unavailable(personTmdbId);
        }

        String profileUrl = TmdbImageUrlBuilder.profileUrl(details.profilePath());
        ContentPreviewStatus status = details.name() != null && profileUrl != null
                ? ContentPreviewStatus.AVAILABLE
                : details.name() != null || profileUrl != null
                        ? ContentPreviewStatus.PARTIAL
                        : ContentPreviewStatus.UNAVAILABLE;
        return new PersonPreviewDTO(personTmdbId, details.name(), profileUrl, status);
    }

    private PersonPreviewDTO unavailable(String personTmdbId) {
        return new PersonPreviewDTO(personTmdbId, null, null, ContentPreviewStatus.UNAVAILABLE);
    }
}
