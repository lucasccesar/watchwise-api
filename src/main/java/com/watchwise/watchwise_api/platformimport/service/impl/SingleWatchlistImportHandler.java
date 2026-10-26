package com.watchwise.watchwise_api.platformimport.service.impl;

import com.watchwise.watchwise_api.common.exception.BadRequestException;
import com.watchwise.watchwise_api.platformimport.model.*;
import com.watchwise.watchwise_api.platformimport.parser.LetterboxdItemCsvParser;
import com.watchwise.watchwise_api.platformimport.parser.LetterboxdListHeader;
import com.watchwise.watchwise_api.platformimport.parser.TraktItemParser;
import com.watchwise.watchwise_api.platformimport.service.ImportModeHandler;
import com.watchwise.watchwise_api.platformimport.storage.ImportFileStore;

import java.io.IOException;
import java.io.BufferedInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.Consumer;

public class SingleWatchlistImportHandler implements ImportModeHandler {
    private final ImportSourceFormat format;
    private final ImportFileStore store;
    private final TraktItemParser traktParser;
    private final LetterboxdItemCsvParser letterboxdParser;

    public SingleWatchlistImportHandler(ImportSourceFormat format, ImportFileStore store,
                                        TraktItemParser traktParser, LetterboxdItemCsvParser letterboxdParser) {
        if (format != ImportSourceFormat.TRAKT_JSON && format != ImportSourceFormat.LETTERBOXD_CSV) {
            throw new IllegalArgumentException("Unsupported standalone watchlist format");
        }
        this.format = format;
        this.store = store;
        this.traktParser = traktParser;
        this.letterboxdParser = letterboxdParser;
    }

    @Override
    public ImportModeKey mode() {
        return new ImportModeKey(ImportInputKind.SINGLE_WATCHLIST, format);
    }

    @Override
    public ImportInventory inspect(ImportReadContext context) {
        return new ImportInventory(List.of(), List.of());
    }

    @Override
    public void normalize(ImportReadContext context, ImportSelection selection,
                          Consumer<NormalizedImportRecord> sink) {
        try (var input = store.open(context.storageKey())) {
            if (format == ImportSourceFormat.TRAKT_JSON) {
                traktParser.parse(input, context, "watchlist.json", ImportRecordKind.WATCHLIST_ITEM, sink);
            } else {
                BufferedInputStream buffered = new BufferedInputStream(input);
                if (LetterboxdListHeader.identifiesList(buffered)) {
                    letterboxdParser.parseList(new InputStreamReader(buffered, StandardCharsets.UTF_8), context,
                            "list-v7.csv", record -> {
                                if (record.kind() != ImportRecordKind.LIST_ITEM) return;
                                String sourceId = record.payload().path("sourceContentUri").asString();
                                sink.accept(new NormalizedImportRecord(record.itemId(), ImportRecordKind.WATCHLIST_ITEM,
                                        new ImportSourceKey(context.sourceNamespace(), "watchlist_content", sourceId),
                                        null, record.target(), record.payload(), record.ordinal()));
                            });
                } else {
                    letterboxdParser.parse(new InputStreamReader(buffered, StandardCharsets.UTF_8), context,
                            "watchlist.csv", ImportRecordKind.WATCHLIST_ITEM, sink);
                }
            }
        } catch (IOException exception) {
            throw new BadRequestException("Standalone watchlist file could not be read");
        }
    }
}
