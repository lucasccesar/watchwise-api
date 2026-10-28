package com.watchwise.watchwise_api.platformimport.service.impl;

import com.watchwise.watchwise_api.platformimport.model.ImportInputKind;
import com.watchwise.watchwise_api.platformimport.model.ImportModeKey;
import com.watchwise.watchwise_api.platformimport.model.ImportReadContext;
import com.watchwise.watchwise_api.platformimport.model.ImportRecordKind;
import com.watchwise.watchwise_api.platformimport.model.ImportSelection;
import com.watchwise.watchwise_api.platformimport.model.ImportSourceFormat;
import com.watchwise.watchwise_api.platformimport.model.NormalizedImportRecord;
import com.watchwise.watchwise_api.platformimport.parser.LetterboxdItemCsvParser;
import com.watchwise.watchwise_api.platformimport.parser.SafeImportReader;
import com.watchwise.watchwise_api.platformimport.parser.impl.LetterboxdArchiveInspector;
import com.watchwise.watchwise_api.platformimport.service.ImportModeHandler;
import com.watchwise.watchwise_api.platformimport.storage.ImportArtifactReader;
import com.watchwise.watchwise_api.platformimport.storage.ReadableImportArtifact;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

public class LetterboxdArchiveImportHandler implements ImportModeHandler {
    private final LetterboxdArchiveInspector inspector;
    private final SafeImportReader reader;
    private final LetterboxdItemCsvParser parser;
    private final ObjectMapper objectMapper;
    private final ImportArtifactReader artifacts;

    public LetterboxdArchiveImportHandler(LetterboxdArchiveInspector inspector, SafeImportReader reader,
                                          LetterboxdItemCsvParser parser, ObjectMapper objectMapper,
                                          ImportArtifactReader artifacts) {
        this.inspector = inspector;
        this.reader = reader;
        this.parser = parser;
        this.objectMapper = objectMapper;
        this.artifacts = artifacts;
    }

    @Override
    public ImportModeKey mode() {
        return new ImportModeKey(ImportInputKind.PLATFORM_ARCHIVE, ImportSourceFormat.LETTERBOXD_ZIP);
    }

    @Override
    public com.watchwise.watchwise_api.platformimport.model.ImportInventory inspect(ImportReadContext context) {
        try (ReadableImportArtifact artifact = artifacts.open(context.artifact())) {
            return inspector.inspect(context, artifact);
        }
    }

    @Override
    public void normalize(ImportReadContext context, ImportSelection selection,
                          Consumer<NormalizedImportRecord> sink) {
        Set<String> selectedLists = selection.listIds();
        try (ReadableImportArtifact artifact = artifacts.open(context.artifact())) {
            reader.forEachEntry(artifact, (path, input) -> {
                String file = path.substring(path.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
                switch (file) {
                    case "diary.csv" -> parseHistoryFile(input, context, path, ImportRecordKind.DIARY_EVENT,
                            selection, sink);
                    case "reviews.csv" -> parseHistoryFile(input, context, path, ImportRecordKind.REVIEW,
                            selection, sink);
                    case "watched.csv" -> parseHistoryFile(input, context, path, ImportRecordKind.WATCHED_MARKER,
                            selection, sink);
                    case "ratings.csv" -> parseHistoryFile(input, context, path, ImportRecordKind.CURRENT_RATING,
                            selection, sink);
                    case "watchlist.csv" -> {
                        if (selection.watchlist()) parser.parse(new InputStreamReader(input, StandardCharsets.UTF_8),
                                context, path, ImportRecordKind.WATCHLIST_ITEM, sink);
                    }
                    case "list-v7.csv" -> {
                        if (!selectedLists.isEmpty()) parseSelectedList(input, context, path, selectedLists, sink);
                    }
                    default -> { }
                }
            });
        }
    }

    private void parseHistoryFile(java.io.InputStream input, ImportReadContext context, String path,
                                  ImportRecordKind kind, ImportSelection selection,
                                  Consumer<NormalizedImportRecord> sink) {
        if (selection.history()) {
            parser.parse(new InputStreamReader(input, StandardCharsets.UTF_8), context, path, kind, sink);
        } else if (selection.watchlist()) {
            parser.parse(new InputStreamReader(input, StandardCharsets.UTF_8), context, path, kind,
                    record -> sink.accept(asAuxiliaryEvidence(record)));
        }
    }

    private void parseSelectedList(java.io.InputStream input, ImportReadContext context, String path,
                                   Set<String> selectedLists, Consumer<NormalizedImportRecord> sink) {
        Set<String> selectedListIdsInFile = new HashSet<>();
        parser.parseList(new InputStreamReader(input, StandardCharsets.UTF_8), context, path, record -> {
            if (record.kind() == ImportRecordKind.LIST_METADATA) {
                if (selectedLists.contains(record.sourceKey().sourceId())) {
                    selectedListIdsInFile.add(record.sourceKey().sourceId());
                    sink.accept(record);
                }
                return;
            }
            if (record.kind() == ImportRecordKind.LIST_ITEM
                    && selectedListIdsInFile.contains(record.parentSourceId())) sink.accept(record);
        });
    }

    private NormalizedImportRecord asAuxiliaryEvidence(NormalizedImportRecord record) {
        var payload = objectMapper.createObjectNode();
        copy(record.payload(), payload, "title");
        copy(record.payload(), payload, "releaseYear");
        copy(record.payload(), payload, "sourceFile");
        copy(record.payload(), payload, "sourceRecordNumber");
        JsonNode sourceQuery = record.payload().path("sourceQuery");
        if (sourceQuery.isObject()) payload.set("sourceQuery", sourceQuery.deepCopy());
        payload.put("auxiliaryWatchEvidence", true);
        String identity = record.sourceKey().objectKind() + ":" + record.sourceKey().sourceId()
                + ":" + record.itemId();
        String sourceId = UUID.nameUUIDFromBytes(identity.getBytes(StandardCharsets.UTF_8)).toString();
        var key = new com.watchwise.watchwise_api.platformimport.model.ImportSourceKey(
                record.sourceKey().namespace(), "auxiliary_watch_marker", sourceId);
        return new NormalizedImportRecord("auxiliary:" + sourceId, ImportRecordKind.WATCHED_MARKER,
                key, null, record.target(), payload, record.ordinal());
    }

    private void copy(JsonNode from, tools.jackson.databind.node.ObjectNode to, String field) {
        JsonNode value = from.get(field);
        if (value != null && !value.isNull()) to.set(field, value.deepCopy());
    }
}
