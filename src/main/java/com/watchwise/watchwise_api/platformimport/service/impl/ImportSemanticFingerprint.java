package com.watchwise.watchwise_api.platformimport.service.impl;

import com.watchwise.watchwise_api.common.exception.BadRequestException;
import com.watchwise.watchwise_api.content.dto.MediaReference;
import com.watchwise.watchwise_api.platformimport.entity.ImportItem;
import com.watchwise.watchwise_api.platformimport.model.ImportTarget;
import com.watchwise.watchwise_api.platformimport.model.ImportedTemplateCategory;
import com.watchwise.watchwise_api.platformimport.model.PreparedListImport;
import com.watchwise.watchwise_api.platformimport.model.TemplateImportDocument;
import com.watchwise.watchwise_api.platformimport.repository.ImportItemRepository;
import com.watchwise.watchwise_api.platformimport.service.ImportExecutionContext;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.OutputStream;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

@Component
public class ImportSemanticFingerprint {
    private static final int PAGE_SIZE = 200;
    private static final UUID FIRST_ID = new UUID(0, 0);
    private final ImportItemRepository items;
    private final ObjectMapper mapper;

    public ImportSemanticFingerprint(ImportItemRepository items, ObjectMapper mapper) {
        this.items = items;
        this.mapper = mapper;
    }

    public String ofList(ImportExecutionContext context, PreparedListImport plan) {
        ImportItem parent = items.findById(plan.parentItemId())
                .filter(item -> context.jobId().equals(item.getJobId())
                        && context.planGeneration() == item.getPlanGeneration())
                .orElseThrow(() -> new IllegalArgumentException("List metadata does not belong to this import"));
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
        try (DigestOutputStream stream = new DigestOutputStream(OutputStream.nullOutputStream(), digest);
             var json = mapper.createGenerator(stream)) {
            json.writeStartObject();
            json.writeStringProperty("version", "standalone-list-v1");
            json.writeStringProperty("name", canonical(plan.name()));
            json.writeStringProperty("description", canonical(plan.description()));
            json.writeArrayPropertyStart("items");
            long afterRank = -1;
            long afterOrdinal = -1;
            UUID afterId = FIRST_ID;
            while (true) {
                List<ImportItem> page = items.findListItemsAfter(context.jobId(), context.planGeneration(),
                        parent.getSourceId(), afterRank, afterOrdinal, afterId.toString(), PAGE_SIZE);
                if (page.isEmpty()) break;
                for (ImportItem item : page) {
                    JsonNode payload = payload(item);
                    JsonNode source = payload.path("source").isObject() ? payload.path("source") : payload;
                    JsonNode edited = payload.path("resolution").path("fields").path("DESCRIPTION");
                    String description = edited.isTextual() ? edited.asString() : text(source, "description");
                    json.writeStartObject();
                    json.writeStringProperty("content", item.getResolvedContentKey());
                    json.writeStringProperty("description", canonical(description));
                    json.writeEndObject();
                    JsonNode rank = source.path("rank");
                    afterRank = rank.isIntegralNumber() && rank.canConvertToLong() && rank.longValue() > 0
                            ? rank.longValue() : Math.addExact(item.getOrdinal(), 1);
                    afterOrdinal = item.getOrdinal();
                    afterId = item.getId();
                }
            }
            json.writeEndArray();
            json.writeEndObject();
        } catch (IOException exception) {
            throw new IllegalStateException("List fingerprint could not be written", exception);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    public String ofTemplate(TemplateImportDocument document, List<List<ImportTarget>> resolvedOptions) {
        if (document == null || resolvedOptions == null
                || document.categories().size() != resolvedOptions.size()) {
            throw new BadRequestException("Template resolutions do not match categories");
        }
        ObjectNode canonical = mapper.createObjectNode();
        canonical.put("kind", "watchwise-template-v1");
        canonical.put("schemaVersion", document.schemaVersion());
        canonical.put("title", canonicalText(document.title()));
        putCanonicalText(canonical, "description", document.description());
        putCanonicalText(canonical, "coverImage", document.coverImage());
        putCanonicalText(canonical, "instructions", document.instructions());
        putDate(canonical, "eligibilityStart", document.eligibilityStart());
        putDate(canonical, "eligibilityEnd", document.eligibilityEnd());
        ArrayNode categories = canonical.putArray("categories");

        for (int index = 0; index < document.categories().size(); index++) {
            ImportedTemplateCategory category = document.categories().get(index);
            List<ImportTarget> targets = resolvedOptions.get(index);
            if (category == null || targets == null || targets.stream().anyMatch(target -> target == null)) {
                throw new BadRequestException("Every fixed nominee requires an explicit resolution");
            }
            if (!category.nomineesPresent() && !targets.isEmpty()) {
                throw new BadRequestException("Open template categories cannot contain fixed nominees");
            }
            ObjectNode categoryJson = categories.addObject();
            categoryJson.put("name", canonicalText(category.name()));
            putCanonicalText(categoryJson, "description", category.description());
            categoryJson.put("type", category.type().name());
            categoryJson.put("group", category.group().name());
            categoryJson.put("displayOrder", category.displayOrder());
            categoryJson.put("optionMode", category.nomineesPresent() ? "FIXED" : "OPEN");

            List<CanonicalTarget> sortedTargets = new ArrayList<>(targets.size());
            for (ImportTarget target : targets) {
                ObjectNode targetJson = targetJson(target);
                sortedTargets.add(new CanonicalTarget(canonicalJson(targetJson), targetJson));
            }
            sortedTargets.sort(Comparator.comparing(CanonicalTarget::sortKey));
            ArrayNode nominees = categoryJson.putArray("nominees");
            sortedTargets.forEach(target -> nominees.add(target.node()));
        }

        try {
            byte[] bytes = mapper.writeValueAsBytes(canonical);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(bytes));
        } catch (JacksonException exception) {
            throw new IllegalStateException("Template fingerprint could not be serialized", exception);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private ObjectNode targetJson(ImportTarget target) {
        ObjectNode result = mapper.createObjectNode();
        if (target instanceof ImportTarget.PersonTarget person) {
            result.put("kind", "PERSON");
            result.put("personTmdbId", person.personTmdbId());
            if (person.contextContent() == null) result.putNull("contextContent");
            else result.set("contextContent", mediaReferenceJson(person.contextContent()));
            return result;
        }
        result.put("kind", "CONTENT");
        result.set("content", mediaReferenceJson(((ImportTarget.ContentTarget) target).content()));
        return result;
    }

    private ObjectNode mediaReferenceJson(MediaReference reference) {
        ObjectNode result = mapper.createObjectNode();
        result.put("type", reference.type().name());
        putNullable(result, "tmdbId", reference.tmdbId());
        putNullable(result, "seriesTmdbId", reference.seriesTmdbId());
        if (reference.seasonNumber() == null) result.putNull("seasonNumber");
        else result.put("seasonNumber", reference.seasonNumber());
        if (reference.episodeNumber() == null) result.putNull("episodeNumber");
        else result.put("episodeNumber", reference.episodeNumber());
        return result;
    }

    private String canonicalJson(JsonNode node) {
        try {
            return mapper.writeValueAsString(node);
        } catch (JacksonException exception) {
            throw new IllegalStateException("Template target identity could not be serialized", exception);
        }
    }

    private void putCanonicalText(ObjectNode node, String field, String value) {
        node.put(field, canonicalText(value));
    }

    private String canonicalText(String value) {
        return value == null ? "" : value.strip();
    }

    private void putDate(ObjectNode node, String field, java.time.LocalDate value) {
        if (value == null) node.putNull(field);
        else node.put(field, value.toString());
    }

    private void putNullable(ObjectNode node, String field, String value) {
        if (value == null) node.putNull(field);
        else node.put(field, value);
    }

    private record CanonicalTarget(String sortKey, ObjectNode node) { }

    private JsonNode payload(ImportItem item) {
        try {
            return mapper.readTree(item.getPayloadJson());
        } catch (JacksonException exception) {
            throw new IllegalStateException("Staged list payload is invalid", exception);
        }
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isTextual() ? value.asString() : null;
    }

    private String canonical(String value) {
        return value == null ? "" : value.strip();
    }
}
