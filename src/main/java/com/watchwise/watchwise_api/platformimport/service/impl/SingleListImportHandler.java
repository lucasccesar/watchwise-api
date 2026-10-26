package com.watchwise.watchwise_api.platformimport.service.impl;

import com.watchwise.watchwise_api.common.exception.BadRequestException;
import com.watchwise.watchwise_api.platformimport.model.*;
import com.watchwise.watchwise_api.platformimport.parser.LetterboxdItemCsvParser;
import com.watchwise.watchwise_api.platformimport.parser.TraktItemParser;
import com.watchwise.watchwise_api.platformimport.service.ImportModeHandler;
import com.watchwise.watchwise_api.platformimport.storage.ImportFileStore;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.BufferedInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.function.Consumer;

public class SingleListImportHandler implements ImportModeHandler {
    private final ImportSourceFormat format;
    private final ImportFileStore store;
    private final TraktItemParser traktParser;
    private final LetterboxdItemCsvParser letterboxdParser;
    private final ObjectMapper mapper;

    public SingleListImportHandler(ImportSourceFormat format, ImportFileStore store,
                                   TraktItemParser traktParser, LetterboxdItemCsvParser letterboxdParser,
                                   ObjectMapper mapper) {
        if (format != ImportSourceFormat.TRAKT_JSON && format != ImportSourceFormat.LETTERBOXD_CSV) {
            throw new IllegalArgumentException("Unsupported standalone list format");
        }
        this.format = format;
        this.store = store;
        this.traktParser = traktParser;
        this.letterboxdParser = letterboxdParser;
        this.mapper = mapper;
    }

    @Override
    public ImportModeKey mode() {
        return new ImportModeKey(ImportInputKind.SINGLE_LIST, format);
    }

    @Override
    public ImportInventory inspect(ImportReadContext context) {
        return new ImportInventory(List.of(), List.of());
    }

    @Override
    public void normalize(ImportReadContext context, ImportSelection selection,
                          Consumer<NormalizedImportRecord> sink) {
        if (format == ImportSourceFormat.LETTERBOXD_CSV) {
            parseLetterboxd(context, selection, sink);
            return;
        }
        parseTrakt(context, selection, sink);
    }

    private void parseLetterboxd(ImportReadContext context, ImportSelection selection,
                                 Consumer<NormalizedImportRecord> sink) {
        try (var input = store.open(context.storageKey()); var buffered = new BufferedInputStream(input)) {
            if (isVersionSevenList(buffered)) {
                if (context.sourceNamespace().contains(":ACCOUNT:")) {
                    letterboxdParser.parseList(new InputStreamReader(buffered, StandardCharsets.UTF_8), context,
                            "list-v7.csv", sink);
                } else {
                    parseLocalLetterboxdList(context, sink);
                }
            } else {
                parseLetterboxdWatchlist(context, selection, sink);
            }
        } catch (IOException exception) {
            throw new BadRequestException("Standalone list file could not be read");
        }
    }

    private boolean isVersionSevenList(BufferedInputStream input) throws IOException {
        input.mark(4);
        int first = input.read();
        if (first == 0xEF) {
            int second = input.read();
            int third = input.read();
            if (second == 0xBB && third == 0xBF) first = input.read();
        }
        input.reset();
        return first == '7';
    }

    private void parseLocalLetterboxdList(ImportReadContext context, Consumer<NormalizedImportRecord> sink) {
        MessageDigest digest = digest();
        readLetterboxdList(context, record -> {
            digest.update(record.kind().name().getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(record.payload().path("title").asString().getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            if (record.kind() == ImportRecordKind.LIST_ITEM) {
                digest.update(record.payload().path("sourceContentUri").asString().getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
            }
        });
        String parentId = HexFormat.of().formatHex(digest.digest());
        readLetterboxdList(context, record -> {
            if (record.kind() == ImportRecordKind.LIST_METADATA) {
                sink.accept(new NormalizedImportRecord("list_metadata:" + parentId, record.kind(),
                        new ImportSourceKey(context.sourceNamespace(), "list_metadata", parentId),
                        null, null, record.payload(), record.ordinal()));
            } else {
                sink.accept(new NormalizedImportRecord("list_item:" + parentId + ":" + record.ordinal(),
                        record.kind(), new ImportSourceKey(context.sourceNamespace(), "list_item",
                        parentId + ":" + record.ordinal()), parentId, record.target(), record.payload(),
                        record.ordinal()));
            }
        });
    }

    private void readLetterboxdList(ImportReadContext context, Consumer<NormalizedImportRecord> sink) {
        try (var input = store.open(context.storageKey())) {
            letterboxdParser.parseList(new InputStreamReader(input, StandardCharsets.UTF_8), context,
                    "list-v7.csv", sink);
        } catch (IOException exception) {
            throw new BadRequestException("Standalone list file could not be read");
        }
    }

    private void parseLetterboxdWatchlist(ImportReadContext context, ImportSelection selection,
                                          Consumer<NormalizedImportRecord> sink) {
        MessageDigest digest = digest();
        long[] count = {0};
        readLetterboxdWatchlist(context, record -> {
            digest.update(record.sourceKey().sourceId().getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(record.payload().path("title").asString().getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            count[0]++;
        });
        String parentId = HexFormat.of().formatHex(digest.digest());
        String namespace = context.sourceNamespace().contains(":ACCOUNT:")
                ? context.sourceNamespace() : localNamespace(context, false);
        readLetterboxdWatchlist(context, record -> sink.accept(new NormalizedImportRecord(
                "list_item:" + record.ordinal() + ":" + parentId, ImportRecordKind.LIST_ITEM,
                new ImportSourceKey(namespace, "list_item", parentId + ":" + record.ordinal()),
                parentId, record.target(), record.payload(), record.ordinal())));
        var payload = mapper.createObjectNode();
        if (selection.metadata() != null && selection.metadata().path("name").isTextual()
                && !selection.metadata().path("name").asString().isBlank()) {
            payload.put("title", selection.metadata().path("name").asString());
        } else {
            payload.put("issueCode", "NEEDS_LIST_NAME");
        }
        sink.accept(new NormalizedImportRecord("list_metadata:" + parentId, ImportRecordKind.LIST_METADATA,
                new ImportSourceKey(namespace, "list_metadata", parentId),
                null, null, payload, Math.toIntExact(count[0])));
    }

    private void readLetterboxdWatchlist(ImportReadContext context, Consumer<NormalizedImportRecord> sink) {
        try (var input = store.open(context.storageKey())) {
            letterboxdParser.parseListItems(new InputStreamReader(input, StandardCharsets.UTF_8), context,
                    "watchlist.csv", sink);
        } catch (IOException exception) {
            throw new BadRequestException("Standalone list file could not be read");
        }
    }

    private void parseTrakt(ImportReadContext context, ImportSelection selection,
                            Consumer<NormalizedImportRecord> sink) {
        if (isTraktListDefinitions(context)) {
            emitMissingItemExport(context, sink);
            return;
        }
        MessageDigest digest = digest();
        long[] count = {0};
        readTrakt(context, record -> {
            digest.update(record.sourceKey().objectKind().getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(record.sourceKey().sourceId().getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(record.payload().path("title").asString().getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            count[0]++;
        });
        if (count[0] == 0) {
            emitMissingItemExport(context, sink);
            return;
        }
        String resolvedParentId = HexFormat.of().formatHex(digest.digest());
        JsonNode suppliedSourceListId = selection.metadata() == null
                ? null : selection.metadata().path("sourceListId");
        if (context.sourceNamespace().contains(":ACCOUNT:")
                && suppliedSourceListId != null && suppliedSourceListId.isTextual()
                && !suppliedSourceListId.asString().isBlank()) {
            resolvedParentId = suppliedSourceListId.asString().trim();
        }
        String parentId = resolvedParentId;
        String namespace = context.sourceNamespace().contains(":ACCOUNT:")
                ? context.sourceNamespace() : localNamespace(context, false);
        readTrakt(context, record -> sink.accept(new NormalizedImportRecord(record.itemId(), record.kind(),
                new ImportSourceKey(namespace, record.sourceKey().objectKind(), record.sourceKey().sourceId()),
                parentId, record.target(), record.payload(), record.ordinal())));
        var payload = mapper.createObjectNode();
        if (selection.metadata() != null) {
            var name = selection.metadata().path("name");
            var description = selection.metadata().path("description");
            if (name.isTextual() && !name.asString().isBlank()) payload.put("title", name.asString());
            if (description.isTextual()) payload.put("description", description.asString());
        }
        if (!payload.path("title").isTextual()) {
            payload.put("issueCode", "NEEDS_LIST_NAME");
        }
        sink.accept(new NormalizedImportRecord("list_metadata:" + parentId, ImportRecordKind.LIST_METADATA,
                new ImportSourceKey(namespace, "list_metadata", parentId),
                null, null, payload, Math.toIntExact(count[0])));
    }

    private void emitMissingItemExport(ImportReadContext context, Consumer<NormalizedImportRecord> sink) {
        var payload = mapper.createObjectNode();
        payload.put("issueCode", "LIST_ITEM_EXPORT_REQUIRED");
        payload.put("issueExplanation", "This file contains list definitions but no items. Upload the list item export.");
        String sourceId = "definitions-only:" + context.jobId();
        sink.accept(new NormalizedImportRecord("list_metadata:" + sourceId, ImportRecordKind.LIST_METADATA,
                new ImportSourceKey(localNamespace(context, false), "list_metadata", sourceId),
                null, null, payload, 0));
    }

    private String localNamespace(ImportReadContext context, boolean strong) {
        if (strong || !context.sourceNamespace().contains(":ACCOUNT:")) return context.sourceNamespace();
        String platform = context.sourceNamespace().substring(0, context.sourceNamespace().indexOf(':'));
        return platform + ":OWNER:" + context.ownerId();
    }

    private boolean isTraktListDefinitions(ImportReadContext context) {
        try (var input = store.open(context.storageKey()); var parser = mapper.createParser(input)) {
            if (parser.nextToken() != JsonToken.START_ARRAY || parser.nextToken() != JsonToken.START_OBJECT) {
                return false;
            }
            boolean name = false;
            boolean ids = false;
            boolean media = false;
            while (parser.nextToken() != JsonToken.END_OBJECT) {
                String field = parser.currentName();
                JsonToken value = parser.nextToken();
                if ("name".equals(field) && value == JsonToken.VALUE_STRING) name = true;
                if ("ids".equals(field) && value == JsonToken.START_OBJECT) ids = true;
                if (("movie".equals(field) || "show".equals(field)) && value == JsonToken.START_OBJECT) {
                    media = true;
                }
                parser.skipChildren();
            }
            return name && ids && !media;
        } catch (IOException | JacksonException exception) {
            return false;
        }
    }

    private void readTrakt(ImportReadContext context, Consumer<NormalizedImportRecord> sink) {
        try (var input = store.open(context.storageKey())) {
            traktParser.parse(input, context, "list.json", ImportRecordKind.LIST_ITEM, sink);
        } catch (IOException exception) {
            throw new BadRequestException("Standalone list file could not be read");
        }
    }

    private MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
