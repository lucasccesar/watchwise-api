package com.watchwise.watchwise_api.platformimport.service.impl;

import com.watchwise.watchwise_api.platformimport.entity.ImportItem;
import com.watchwise.watchwise_api.platformimport.model.PreparedListImport;
import com.watchwise.watchwise_api.platformimport.repository.ImportItemRepository;
import com.watchwise.watchwise_api.platformimport.service.ImportExecutionContext;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.OutputStream;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
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
