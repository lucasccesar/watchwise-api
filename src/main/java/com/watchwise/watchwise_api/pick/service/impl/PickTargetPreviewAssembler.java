package com.watchwise.watchwise_api.pick.service.impl;

import com.watchwise.watchwise_api.common.tmdb.TmdbClient;
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
import com.watchwise.watchwise_api.person.dto.PersonPreviewDTO;
import com.watchwise.watchwise_api.person.service.PersonPreviewResolver;
import com.watchwise.watchwise_api.person.service.impl.TmdbPersonPreviewResolver;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executor;

@Component
public final class PickTargetPreviewAssembler {
    private final ContentCardAssembler contentCardAssembler;
    private final PersonPreviewResolver personPreviewResolver;

    @Autowired
    public PickTargetPreviewAssembler(
            ContentCardAssembler contentCardAssembler,
            PersonPreviewResolver personPreviewResolver) {
        this.contentCardAssembler = contentCardAssembler;
        this.personPreviewResolver = personPreviewResolver;
    }

    public PickTargetPreviewAssembler(ContentCardAssembler contentCardAssembler, TmdbClient tmdbClient) {
        this(contentCardAssembler, new TmdbPersonPreviewResolver(tmdbClient, Runnable::run));
    }

    public PickTargetPreviewAssembler(
            ContentCardAssembler contentCardAssembler,
            TmdbClient tmdbClient,
            Executor metadataResolutionExecutor) {
        this(contentCardAssembler, new TmdbPersonPreviewResolver(tmdbClient, metadataResolutionExecutor));
    }

    public List<PickTargetPreviewDTO> assemble(
            List<PickOptionSearchDTO> targets,
            ContentCardContext cardContext) {
        Objects.requireNonNull(cardContext, "cardContext is required");
        if (targets == null || targets.isEmpty()) {
            return List.of();
        }

        Map<ContentCoordinate, ContentCardSpec> contentSpecs = new LinkedHashMap<>();
        List<String> personIds = new ArrayList<>();
        for (PickOptionSearchDTO target : targets) {
            if (target == null) {
                throw new IllegalArgumentException("target is required");
            }
            if (target.personTmdbId() != null) {
                personIds.add(target.personTmdbId());
                addContentSpec(target.contextContent(), contentSpecs);
            } else if (target.content() != null) {
                addContentSpec(target.content(), contentSpecs);
            } else {
                throw new IllegalArgumentException("target must contain content or person");
            }
        }

        Map<ContentCoordinate, ContentCardDTO> cards = contentCardAssembler.assemble(
                contentSpecs.values(), cardContext, ContentCardFieldSet.BASIC_METADATA);
        Map<String, PersonPreviewDTO> personPreviews = personIds.isEmpty()
                ? Map.of()
                : personPreviewResolver.resolve(personIds);

        List<PickTargetPreviewDTO> previews = new ArrayList<>(targets.size());
        for (PickOptionSearchDTO target : targets) {
            if (target.personTmdbId() != null) {
                previews.add(new PickTargetPreviewDTO(null, personPreview(target.personTmdbId(), personPreviews),
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

    private PickPersonPreviewDTO personPreview(String personId, Map<String, PersonPreviewDTO> personPreviews) {
        PersonPreviewDTO preview = personPreviews.get(personId);
        if (preview == null) {
            return new PickPersonPreviewDTO(personId, null, null, ContentPreviewStatus.UNAVAILABLE);
        }
        return new PickPersonPreviewDTO(preview.personTmdbId(), preview.name(), preview.profileUrl(),
                preview.previewStatus());
    }
}
