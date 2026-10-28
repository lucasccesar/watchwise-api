package com.watchwise.watchwise_api.platformimport.service.impl;

import com.watchwise.watchwise_api.common.exception.BadRequestException;
import com.watchwise.watchwise_api.platformimport.model.ImportInputKind;
import com.watchwise.watchwise_api.platformimport.model.ImportModeKey;
import com.watchwise.watchwise_api.platformimport.model.ImportReadContext;
import com.watchwise.watchwise_api.platformimport.model.ImportRecordKind;
import com.watchwise.watchwise_api.platformimport.model.ImportSelection;
import com.watchwise.watchwise_api.platformimport.model.ImportSourceFormat;
import com.watchwise.watchwise_api.platformimport.model.NormalizedImportRecord;
import com.watchwise.watchwise_api.platformimport.parser.SafeImportReader;
import com.watchwise.watchwise_api.platformimport.parser.TraktItemParser;
import com.watchwise.watchwise_api.platformimport.parser.impl.TraktArchiveInspector;
import com.watchwise.watchwise_api.platformimport.service.ImportModeHandler;
import com.watchwise.watchwise_api.platformimport.storage.ImportArtifactReader;
import com.watchwise.watchwise_api.platformimport.storage.ReadableImportArtifact;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.math.BigInteger;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class TraktArchiveImportHandler implements ImportModeHandler {
    private static final Pattern ARRAY_FILE = Pattern.compile("(?:.*/)?%s(?:-\\d+)?\\.json", Pattern.CASE_INSENSITIVE);
    private static final Pattern LIST_ITEMS = Pattern.compile("(?:^|/)lists-list-(\\d+)(?:-sample)?(?:-[a-z0-9][a-z0-9-]*)?\\.json$", Pattern.CASE_INSENSITIVE);
    private static final Pattern LIST_METADATA = Pattern.compile("(?:.*/)?lists-lists(?:-\\d+)?\\.json", Pattern.CASE_INSENSITIVE);

    private final TraktArchiveInspector inspector;
    private final SafeImportReader reader;
    private final TraktItemParser parser;
    private final ObjectMapper objectMapper;
    private final ImportArtifactReader artifacts;

    public TraktArchiveImportHandler(TraktArchiveInspector inspector, SafeImportReader reader,
                                     TraktItemParser parser, ObjectMapper objectMapper,
                                     ImportArtifactReader artifacts) {
        this.inspector = inspector;
        this.reader = reader;
        this.parser = parser;
        this.objectMapper = objectMapper;
        this.artifacts = artifacts;
    }

    @Override
    public ImportModeKey mode() {
        return new ImportModeKey(ImportInputKind.PLATFORM_ARCHIVE, ImportSourceFormat.TRAKT_ZIP);
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
        Set<String> selectedLists = canonicalListIds(selection.listIds());
        Set<String> emittedListMetadata = new HashSet<>();
        try (ReadableImportArtifact artifact = artifacts.open(context.artifact())) {
            reader.forEachEntry(artifact, (path, input) -> {
                String file = path.toLowerCase(Locale.ROOT);
                if (LIST_METADATA.matcher(file).matches() && !selectedLists.isEmpty()) {
                    inspector.parseSelectedListMetadata(input, path, context, selectedLists, record -> {
                        if (emittedListMetadata.add(record.sourceKey().sourceId())) sink.accept(record);
                    });
                    return;
                }

                if (matches(file, "watched-history")) {
                    if (selection.history()) {
                        parser.parse(input, context, path, ImportRecordKind.DIARY_EVENT, sink);
                    } else if (selection.watchlist()) {
                        parseAuxiliary(input, context, path, ImportRecordKind.WATCHED_MARKER, sink);
                    }
                    return;
                }

                if (matches(file, "watched-movies") || matches(file, "watched-shows")) {
                    if (selection.history()) {
                        parser.parse(input, context, path, ImportRecordKind.WATCHED_SUMMARY, sink);
                    } else if (selection.watchlist()) {
                        parseAuxiliary(input, context, path, ImportRecordKind.WATCHED_MARKER, sink);
                    }
                    return;
                }

                if (matches(file, "watched-episodes") || matches(file, "watched-seasons")) {
                    if (selection.history() || selection.watchlist()) {
                        if (selection.history()) {
                            parser.parse(input, context, path, ImportRecordKind.WATCHED_MARKER, sink);
                        } else {
                            parseAuxiliary(input, context, path, ImportRecordKind.WATCHED_MARKER, sink);
                        }
                    }
                    return;
                }

                if (matches(file, "ratings-movies") || matches(file, "ratings-shows")
                        || matches(file, "ratings-episodes") || matches(file, "ratings-seasons")) {
                    if (selection.history()) {
                        parser.parse(input, context, path, ImportRecordKind.CURRENT_RATING, sink);
                    }
                    return;
                }

                if (matches(file, "lists-watchlist")) {
                    if (selection.watchlist()) {
                        parser.parse(input, context, path, ImportRecordKind.WATCHLIST_ITEM, sink);
                    }
                    return;
                }

                String listId = listId(path);
                if (listId != null && selectedLists.contains(listId)) {
                    parser.parse(input, context, path, ImportRecordKind.LIST_ITEM,
                            record -> sink.accept(withParent(record, listId)));
                }
            });
        }
    }

    private void parseAuxiliary(InputStream input, ImportReadContext context, String path,
                                ImportRecordKind kind, Consumer<NormalizedImportRecord> sink) {
        parser.parse(input, context, path, kind, record -> sink.accept(asAuxiliaryEvidence(record)));
    }

    private NormalizedImportRecord withParent(NormalizedImportRecord record, String listId) {
        return new NormalizedImportRecord(record.itemId(), record.kind(), record.sourceKey(), listId,
                record.target(), record.payload(), record.ordinal());
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
        String sourceId = java.util.UUID.nameUUIDFromBytes(identity.getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
        var key = new com.watchwise.watchwise_api.platformimport.model.ImportSourceKey(
                record.sourceKey().namespace(), "auxiliary_watch_marker", sourceId);
        return new NormalizedImportRecord("auxiliary:" + sourceId, ImportRecordKind.WATCHED_MARKER,
                key, null, record.target(), payload, record.ordinal());
    }

    private void copy(JsonNode from, tools.jackson.databind.node.ObjectNode to, String field) {
        JsonNode value = from.get(field);
        if (value != null && !value.isNull()) to.set(field, value.deepCopy());
    }

    private Set<String> canonicalListIds(Set<String> ids) {
        Set<String> canonical = new HashSet<>();
        for (String id : ids) {
            try {
                if (!id.matches("[0-9]+") || id.chars().allMatch(c -> c == '0')) throw new NumberFormatException();
                canonical.add(new BigInteger(id).toString());
            } catch (NumberFormatException exception) {
                throw new BadRequestException("Invalid Trakt list id");
            }
        }
        return Set.copyOf(canonical);
    }

    private String listId(String path) {
        Matcher matcher = LIST_ITEMS.matcher(path);
        if (!matcher.matches()) return null;
        return new BigInteger(matcher.group(1)).toString();
    }

    private boolean matches(String file, String stem) {
        return Pattern.matches(ARRAY_FILE.pattern().formatted(Pattern.quote(stem)), file);
    }
}
