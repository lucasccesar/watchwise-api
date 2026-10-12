package com.watchwise.watchwise_api.pick.service.impl;

import com.watchwise.watchwise_api.common.tmdb.TmdbClient;
import com.watchwise.watchwise_api.common.tmdb.TmdbImageUrlBuilder;
import com.watchwise.watchwise_api.common.tmdb.TmdbLookupResult;
import com.watchwise.watchwise_api.common.tmdb.TmdbPersonDetails;
import com.watchwise.watchwise_api.content.dto.ContentCardDTO;
import com.watchwise.watchwise_api.content.dto.ContentCardFieldSet;
import com.watchwise.watchwise_api.content.dto.ContentPreviewStatus;
import com.watchwise.watchwise_api.content.dto.ContentRefDTO;
import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.content.service.ContentCardContext;
import com.watchwise.watchwise_api.content.service.ContentCardSpec;
import com.watchwise.watchwise_api.content.service.ContentCoordinate;
import com.watchwise.watchwise_api.content.service.impl.ContentCardAssembler;
import com.watchwise.watchwise_api.pick.dto.PickOptionSearchDTO;
import com.watchwise.watchwise_api.pick.dto.PickPersonPreviewDTO;
import com.watchwise.watchwise_api.pick.dto.PickTargetPreviewDTO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;

@Component
public final class PickTargetPreviewAssembler {
    private final ContentCardAssembler contentCardAssembler;
    private final TmdbClient tmdbClient;
    private final Executor metadataResolutionExecutor;

    @Autowired
    public PickTargetPreviewAssembler(
            ContentCardAssembler contentCardAssembler,
            TmdbClient tmdbClient,
            @Qualifier("contentCardMetadataResolutionExecutor") Executor metadataResolutionExecutor) {
        this.contentCardAssembler = contentCardAssembler;
        this.tmdbClient = tmdbClient;
        this.metadataResolutionExecutor = metadataResolutionExecutor;
    }

    public PickTargetPreviewAssembler(ContentCardAssembler contentCardAssembler, TmdbClient tmdbClient) {
        this(contentCardAssembler, tmdbClient, Runnable::run);
    }

    public List<PickTargetPreviewDTO> assemble(
            List<PickOptionSearchDTO> targets,
            ContentCardContext cardContext) {
        Objects.requireNonNull(cardContext, "cardContext is required");
        if (targets == null || targets.isEmpty()) {
            return List.of();
        }

        Map<ContentCoordinate, ContentCardSpec> contentSpecs = new LinkedHashMap<>();
        Map<String, CompletableFuture<PickPersonPreviewDTO>> people = new LinkedHashMap<>();
        for (PickOptionSearchDTO target : targets) {
            if (target == null) {
                throw new IllegalArgumentException("target is required");
            }
            if (target.personTmdbId() != null) {
                people.computeIfAbsent(target.personTmdbId(), personId -> CompletableFuture.supplyAsync(
                        () -> personPreview(personId), metadataResolutionExecutor));
                addContentSpec(target.contextContent(), contentSpecs);
            } else if (target.content() != null) {
                addContentSpec(target.content(), contentSpecs);
            } else {
                throw new IllegalArgumentException("target must contain content or person");
            }
        }

        Map<ContentCoordinate, ContentCardDTO> cards = contentCardAssembler.assemble(
                contentSpecs.values(), cardContext, ContentCardFieldSet.BASIC_METADATA);
        Map<String, PickPersonPreviewDTO> personPreviews = new LinkedHashMap<>();
        people.forEach((personId, future) -> personPreviews.put(personId, join(future)));

        List<PickTargetPreviewDTO> previews = new ArrayList<>(targets.size());
        for (PickOptionSearchDTO target : targets) {
            if (target.personTmdbId() != null) {
                previews.add(new PickTargetPreviewDTO(null, personPreviews.get(target.personTmdbId()),
                        card(target.contextContent(), cards)));
            } else {
                previews.add(new PickTargetPreviewDTO(card(target.content(), cards), null, null));
            }
        }
        return List.copyOf(previews);
    }

    private void addContentSpec(ContentRefDTO reference, Map<ContentCoordinate, ContentCardSpec> specs) {
        if (reference == null) {
            return;
        }
        ContentCoordinate coordinate = coordinate(reference);
        specs.putIfAbsent(coordinate, new ContentCardSpec(coordinate, null, null, null,
                reference.runtimeMinutes(), reference.genres()));
    }

    private ContentCardDTO card(ContentRefDTO reference, Map<ContentCoordinate, ContentCardDTO> cards) {
        if (reference == null) {
            return null;
        }
        ContentCoordinate coordinate = coordinate(reference);
        ContentCardDTO card = cards.get(coordinate);
        if (card != null) {
            return card;
        }
        return new ContentCardDTO(null, coordinate.type(), coordinate.tmdbId(), coordinate.seriesTmdbId(),
                coordinate.seasonNumber(), coordinate.episodeNumber(), null, null, null, null, null, null,
                null, null, null, null, null, null, ContentPreviewStatus.UNAVAILABLE);
    }

    private ContentCoordinate coordinate(ContentRefDTO reference) {
        ContentType type = Objects.requireNonNull(reference.type(), "content type is required");
        return new ContentCoordinate(type, reference.tmdbId(), reference.seriesTmdbId(),
                reference.seasonNumber(), reference.episodeNumber());
    }

    private PickPersonPreviewDTO personPreview(String personId) {
        TmdbLookupResult<TmdbPersonDetails> lookup = tmdbClient.getPersonDetails(personId);
        TmdbPersonDetails details = lookup.toOptional().orElse(null);
        String name = details == null ? null : details.name();
        String profileUrl = details == null ? null : TmdbImageUrlBuilder.profileUrl(details.profilePath());
        ContentPreviewStatus status = details == null
                ? ContentPreviewStatus.UNAVAILABLE
                : name != null && profileUrl != null
                        ? ContentPreviewStatus.AVAILABLE
                        : name != null || profileUrl != null
                                ? ContentPreviewStatus.PARTIAL
                                : ContentPreviewStatus.UNAVAILABLE;
        return new PickPersonPreviewDTO(personId, name, profileUrl, status);
    }

    private PickPersonPreviewDTO join(CompletableFuture<PickPersonPreviewDTO> future) {
        try {
            return future.join();
        } catch (CompletionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw exception;
        }
    }
}
