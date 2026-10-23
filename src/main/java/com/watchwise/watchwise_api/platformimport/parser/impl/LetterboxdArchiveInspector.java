package com.watchwise.watchwise_api.platformimport.parser.impl;

import com.watchwise.watchwise_api.common.exception.BadRequestException;
import com.watchwise.watchwise_api.platformimport.model.*;
import com.watchwise.watchwise_api.platformimport.parser.CsvRecordReader;
import com.watchwise.watchwise_api.platformimport.parser.SafeImportReader;
import com.watchwise.watchwise_api.platformimport.service.ImportLimits;
import com.watchwise.watchwise_api.platformimport.storage.PrivateImportFileStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

@Component
public class LetterboxdArchiveInspector {
    private final SafeImportReader archiveReader;
    private final CsvRecordReader csv;
    private final LetterboxdItemCsvParserImpl items;

    public LetterboxdArchiveInspector(CsvRecordReader csv) { this(null, csv); }
    public LetterboxdArchiveInspector(SafeImportReader reader, CsvRecordReader csv) {
        this.archiveReader = reader;
        this.csv = csv;
        this.items = new LetterboxdItemCsvParserImpl(csv);
    }

    @Autowired
    public LetterboxdArchiveInspector(PrivateImportFileStore store, ImportLimits limits, CsvRecordReader csv) {
        this(new SafeImportReader(store, limits), csv);
    }

    public ImportInventory inspect(ImportReadContext context) {
        if (archiveReader == null) throw new IllegalStateException("Archive inspection requires a safe import reader");
        Map<ImportRecordKind, Long> counts = zeroCounts();
        List<ImportListInventory> lists = new ArrayList<>();
        archiveReader.forEachEntry(context.storageKey(), (path, input) -> inspectEntry(path, input, context, counts, lists));
        return inventory(counts, lists);
    }

    public ImportInventory inspectFiles(Map<String, InputStream> files) {
        Map<ImportRecordKind, Long> counts = zeroCounts();
        List<ImportListInventory> lists = new ArrayList<>();
        files.forEach((path, input) -> inspectEntry(path, input, null, counts, lists));
        return inventory(counts, lists);
    }

    private void inspectEntry(String path, InputStream input, ImportReadContext context,
                              Map<ImportRecordKind, Long> counts, List<ImportListInventory> lists) {
        String filename = path.substring(path.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
        ImportRecordKind kind = switch (filename) {
            case "diary.csv" -> ImportRecordKind.DIARY_EVENT;
            case "reviews.csv" -> ImportRecordKind.REVIEW;
            case "watched.csv" -> ImportRecordKind.WATCHED_MARKER;
            case "ratings.csv" -> ImportRecordKind.CURRENT_RATING;
            case "watchlist.csv" -> ImportRecordKind.WATCHLIST_ITEM;
            default -> null;
        };
        try {
            if (kind != null) {
                long[] rowCount = {0};
                csv.read(new InputStreamReader(input, StandardCharsets.UTF_8), row -> {
                    if (rowCount[0] == 0) {
                        rowCount[0]++;
                        return;
                    }
                    if (!row.isEmpty() && row.stream().anyMatch(cell -> !cell.isEmpty())) rowCount[0]++;
                });
                if (rowCount[0] == 0) throw new BadRequestException(path + " record 1 field header: CSV header is missing");
                counts.merge(kind, rowCount[0] - 1, Long::sum);
            } else if (filename.equals("list-v7.csv")) {
                long[] itemCount = {0};
                var metadata = items.parseList(new InputStreamReader(input, StandardCharsets.UTF_8),
                        context == null ? syntheticContext() : context, path, record -> {
                            if (record.kind() == ImportRecordKind.LIST_ITEM) itemCount[0]++;
                        });
                if (metadata.isEmpty()) lists.add(new ImportListInventory(path, null, null, itemCount[0], "COMPLETE"));
                else for (var list : metadata) lists.add(new ImportListInventory(
                        list.sourceUri() == null ? path : list.sourceUri(), list.name(), list.description(), itemCount[0], "COMPLETE"));
                counts.merge(ImportRecordKind.LIST_ITEM, itemCount[0], Long::sum);
            }
        } catch (UncheckedIOException ex) {
            throw new BadRequestException(path + " field csv: malformed CSV input");
        }
    }

    private ImportReadContext syntheticContext() {
        return new ImportReadContext(new UUID(0, 1), new UUID(0, 2), "in-memory", "letterboxd", java.time.ZoneId.of("UTC"));
    }

    private Map<ImportRecordKind, Long> zeroCounts() {
        Map<ImportRecordKind, Long> counts = new EnumMap<>(ImportRecordKind.class);
        for (ImportRecordKind kind : List.of(ImportRecordKind.DIARY_EVENT, ImportRecordKind.REVIEW,
                ImportRecordKind.WATCHED_MARKER, ImportRecordKind.CURRENT_RATING, ImportRecordKind.WATCHLIST_ITEM,
                ImportRecordKind.LIST_ITEM)) counts.put(kind, 0L);
        return counts;
    }

    private ImportInventory inventory(Map<ImportRecordKind, Long> counts, List<ImportListInventory> lists) {
        List<ImportCategoryInventory> categories = counts.entrySet().stream()
                .map(entry -> new ImportCategoryInventory(entry.getKey(), entry.getValue(), entry.getValue() > 0)).toList();
        return new ImportInventory(categories, lists);
    }
}
