package com.watchwise.watchwise_api.platformimport.service.impl;

import com.watchwise.watchwise_api.content.dto.MediaReference;
import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.pickstemplate.entity.PickAllowedType;
import com.watchwise.watchwise_api.platformimport.model.*;
import com.watchwise.watchwise_api.platformimport.parser.TemplateImportEnumValues;
import com.watchwise.watchwise_api.platformimport.service.ExternalContentAliasService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class TemplateNomineeResolver {
    private final ExternalContentAliasService aliases;

    public AliasResolution resolve(UUID owner, PickAllowedType type, JsonNode nominee) {
        if (owner == null || type == null || nominee == null) throw new IllegalArgumentException("Nominee resolution requires owner, type and value");
        if (nominee.isTextual()) {
            return aliases.resolve(owner, type == PickAllowedType.PERSON
                    ? AliasQuery.person(nominee.asString())
                    : new AliasQuery(nominee.asString(), null, contentType(type)));
        }
        if (!nominee.isObject()) throw new IllegalArgumentException("Nominee must be a string or object");
        if (type == PickAllowedType.PERSON) {
            String id = text(nominee, "personTmdbId");
            MediaReference context = explicitPersonContext(nominee);
            if (id != null) return AliasResolution.matched(new ImportTarget.PersonTarget(id, context));
            return preservePersonContext(aliases.resolve(owner, AliasQuery.person(text(nominee, "name"))), context);
        }
        if (type == PickAllowedType.EPISODE) {
            return AliasResolution.matched(new ImportTarget.ContentTarget(MediaReference.episode(
                    text(nominee, "seriesTmdbId"), number(nominee, "seasonNumber"), number(nominee, "episodeNumber"))));
        }
        String id = text(nominee, "tmdbId");
        if (id != null) return AliasResolution.matched(new ImportTarget.ContentTarget(type == PickAllowedType.MOVIE
                ? MediaReference.movie(id) : MediaReference.series(id)));
        return aliases.resolve(owner, new AliasQuery(text(nominee, "title"), number(nominee, "releaseYear"), contentType(type)));
    }

    public MediaReference explicitPersonContext(JsonNode nominee) {
        return nominee == null || !nominee.isObject() ? null : context(nominee.get("contextContent"));
    }

    private static MediaReference context(JsonNode value) {
        if (value == null) return null;
        PickAllowedType type = TemplateImportEnumValues.enumValue(value, "type", PickAllowedType.class, null);
        return switch (type) {
            case MOVIE -> MediaReference.movie(text(value, "tmdbId"));
            case SERIES -> MediaReference.series(text(value, "tmdbId"));
            case EPISODE -> MediaReference.episode(text(value, "seriesTmdbId"), number(value, "seasonNumber"), number(value, "episodeNumber"));
            case PERSON -> throw new IllegalArgumentException("Person context must be content");
        };
    }

    private static AliasResolution preservePersonContext(AliasResolution resolution, MediaReference context) {
        if (context == null) return resolution;
        List<ImportTarget> candidates = resolution.candidates().stream()
                .map(candidate -> {
                    if (candidate instanceof ImportTarget.PersonTarget person) {
                        return (ImportTarget) new ImportTarget.PersonTarget(person.personTmdbId(), context);
                    }
                    throw new IllegalStateException("Person alias returned a non-person target");
                }).toList();
        return new AliasResolution(resolution.status(), candidates);
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null ? null : value.asString();
    }

    private static int number(JsonNode node, String field) {
        return node.get(field).asInt();
    }

    private static ContentType contentType(PickAllowedType type) {
        if (type == PickAllowedType.PERSON) {
            throw new IllegalArgumentException("Person uses a separate alias namespace");
        }
        return type.toContentType();
    }
}
