package com.watchwise.watchwise_api.platformimport.parser.impl;

import com.watchwise.watchwise_api.common.exception.BadRequestException;
import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.platformimport.model.*;
import com.watchwise.watchwise_api.platformimport.parser.CsvRecordReader;
import com.watchwise.watchwise_api.platformimport.parser.LetterboxdItemCsvParser;
import com.watchwise.watchwise_api.platformimport.parser.ParsedListMetadata;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.io.Reader;
import java.time.LocalDate;
import java.util.*;
import java.util.function.Consumer;

@Component
public class LetterboxdItemCsvParserImpl implements LetterboxdItemCsvParser {
    private static final List<String> DIARY_HEADER = List.of("Date", "Name", "Year", "Letterboxd URI", "Rating", "Rewatch", "Tags", "Watched Date");
    private static final List<String> REVIEW_HEADER = List.of("Date", "Name", "Year", "Letterboxd URI", "Rating", "Review");
    private static final List<String> WATCHED_HEADER = List.of("Date", "Name", "Year", "Letterboxd URI");
    private static final List<String> RATING_HEADER = List.of("Date", "Name", "Year", "Letterboxd URI", "Rating");
    private static final List<String> WATCHLIST_HEADER = WATCHED_HEADER;
    private static final List<String> LIST_METADATA_HEADER = List.of("Name", "Description", "Tags", "URL");
    private static final List<String> LIST_ITEM_HEADER = List.of("Date", "Name", "Year", "Letterboxd URI", "Rating", "Watched Date");
    private final CsvRecordReader csv;
    private final ObjectMapper mapper;

    public LetterboxdItemCsvParserImpl(CsvRecordReader csv) { this(csv, new ObjectMapper()); }
    public LetterboxdItemCsvParserImpl(CsvRecordReader csv, ObjectMapper mapper) { this.csv = csv; this.mapper = mapper; }

    @Override
    public void parse(Reader input, ImportReadContext context, String sourcePath, ImportRecordKind kind,
                      Consumer<NormalizedImportRecord> sink) {
        if (kind == ImportRecordKind.LIST_ITEM) {
            parseList(input, context, sourcePath, sink);
            return;
        }
        List<String> expected = header(kind);
        boolean[] headerSeen = {false};
        long[] rowNumber = {0};
        csv.read(input, row -> {
            if (row.isEmpty() || row.stream().allMatch(String::isEmpty)) return;
            rowNumber[0]++;
            if (!headerSeen[0]) {
                if (!expected.equals(row)) throw failure(sourcePath, rowNumber[0], "header", "Unexpected CSV header");
                headerSeen[0] = true;
                return;
            }
            sink.accept(normalize(row, expected, context, sourcePath, kind, Math.toIntExact(rowNumber[0] - 2), null));
        });
        if (!headerSeen[0]) throw failure(sourcePath, 1, "header", "CSV header is missing");
    }

    public List<ParsedListMetadata> parseList(Reader input, ImportReadContext context, String sourcePath,
                                               Consumer<NormalizedImportRecord> sink) {
        List<ParsedListMetadata> found = new ArrayList<>();
        List<List<String>> itemHeaders = new ArrayList<>(1);
        String[] listSourceId = {sourcePath};
        ListState[] state = {ListState.VERSION};
        long[] recordNo = {0};
        long[] itemOrdinal = {0};
        csv.read(input, row -> {
            recordNo[0]++;
            if (row.isEmpty() || row.stream().allMatch(String::isEmpty)) return;
            switch (state[0]) {
                case VERSION -> {
                    if (row.size() != 1 || !"7".equals(row.getFirst()))
                        throw failure(sourcePath, recordNo[0], "version", "Expected Letterboxd list version 7");
                    state[0] = ListState.METADATA_HEADER;
                }
                case METADATA_HEADER -> {
                    if (!LIST_METADATA_HEADER.equals(row)) throw failure(sourcePath, recordNo[0], "metadata.header", "Unexpected list metadata header");
                    state[0] = ListState.METADATA_ROW;
                }
                case METADATA_ROW -> {
                    requireSize(row, LIST_METADATA_HEADER, sourcePath, recordNo[0]);
                    ParsedListMetadata parsed = new ParsedListMetadata(row.get(0), blankToNull(row.get(1)), splitTags(row.get(2)), blankToNull(row.get(3)));
                    found.add(parsed);
                    listSourceId[0] = parsed.sourceUri() == null ? sourcePath : parsed.sourceUri();
                    var payload = mapper.createObjectNode();
                    payload.put("title", parsed.name());
                    if (parsed.description() != null) payload.put("description", parsed.description());
                    payload.set("tags", mapper.valueToTree(parsed.tags()));
                    if (parsed.sourceUri() != null) payload.put("sourceUri", parsed.sourceUri());
                    payload.put("sourceFile", sourcePath);
                    ImportSourceKey key = new ImportSourceKey(context.sourceNamespace(), "list_metadata", parsed.sourceUri() == null ? sourcePath : parsed.sourceUri());
                    sink.accept(new NormalizedImportRecord("list_metadata:" + key.sourceId(), ImportRecordKind.LIST_METADATA,
                            key, null, null, withQuery(payload, parsed.name()), 0));
                    state[0] = ListState.ITEMS_HEADER;
                }
                case ITEMS_HEADER -> {
                    if (!LIST_ITEM_HEADER.equals(row)) throw failure(sourcePath, recordNo[0], "items.header", "Unexpected list items header");
                    itemHeaders.add(row);
                    state[0] = ListState.ITEMS;
                }
                case ITEMS -> {
                    requireSize(row, itemHeaders.getFirst(), sourcePath, recordNo[0]);
                    sink.accept(normalize(row, itemHeaders.getFirst(), context, sourcePath, ImportRecordKind.LIST_ITEM,
                            Math.toIntExact(itemOrdinal[0]++), listSourceId[0]));
                }
            }
        });
        if (state[0] != ListState.ITEMS) throw failure(sourcePath, recordNo[0], "section", "List metadata/items sections are incomplete");
        return List.copyOf(found);
    }

    private NormalizedImportRecord normalize(List<String> row, List<String> header, ImportReadContext context,
                                             String path, ImportRecordKind kind, int ordinal, String parentSourceId) {
        requireSize(row, header, path, ordinal + 2L);
        Map<String, String> values = new HashMap<>();
        for (int i = 0; i < header.size(); i++) values.put(header.get(i), row.get(i));
        String title = values.get("Name");
        if (title == null || title.isBlank()) throw failure(path, ordinal + 2L, "Name", "Title is required");
        String uri = values.get("Letterboxd URI");
        if (uri == null || uri.isBlank()) throw failure(path, ordinal + 2L, "Letterboxd URI", "URI is required");
        Integer year = parseYear(values.get("Year"), path, ordinal + 2L);
        String date = date(values.get("Date"), path, ordinal + 2L, "Date");
        String watched = values.containsKey("Watched Date") ? date(values.get("Watched Date"), path, ordinal + 2L, "Watched Date") : null;
        Integer score = values.containsKey("Rating") ? LetterboxdHistoryCsvParser.parseScore(values.get("Rating"), path, ordinal + 2L) : null;
        var payload = mapper.createObjectNode();
        payload.put("title", title);
        if (year != null) payload.put("releaseYear", year);
        payload.put("sourceContentUri", uri);
        payload.put("sourceFile", path);
        payload.put("sourceRecordNumber", ordinal + 2L);
        if (date != null) payload.put("sourceRecordedOn", date);
        if (watched != null) payload.put("watchedOn", watched);
        if (score != null) payload.put("score", score);
        if (values.containsKey("Review") && !values.get("Review").isEmpty()) payload.put("review", values.get("Review"));
        if (values.containsKey("Rewatch") && !values.get("Rewatch").isEmpty()) payload.put("sourceRewatch", Boolean.parseBoolean(values.get("Rewatch")));
        if (values.containsKey("Tags")) payload.set("tags", mapper.valueToTree(splitTags(values.get("Tags"))));
        if (kind == ImportRecordKind.DIARY_EVENT || kind == ImportRecordKind.REVIEW) payload.put("sourceUri", uri);
        payload.set("sourceQuery", contentQuery(title, year));
        String id = kind == ImportRecordKind.LIST_ITEM ? parentSourceId + ":" + ordinal : uri;
        String objectKind = switch (kind) {
            case DIARY_EVENT, REVIEW -> "diary_event";
            case WATCHED_MARKER -> "watched_content";
            case CURRENT_RATING -> "rated_content";
            case WATCHLIST_ITEM -> "watchlist_content";
            case LIST_ITEM -> "list_item";
            default -> kind.name().toLowerCase(Locale.ROOT);
        };
        return new NormalizedImportRecord(kind.name().toLowerCase(Locale.ROOT) + ":" + id, kind,
                new ImportSourceKey(context.sourceNamespace(), objectKind, id), parentSourceId, null, payload, ordinal);
    }

    private tools.jackson.databind.node.ObjectNode contentQuery(String title, Integer year) {
        var query = mapper.createObjectNode().put("kind", "CONTENT").put("title", title).put("contentType", ContentType.MOVIE.name());
        if (year != null) query.put("year", year);
        return query;
    }

    private tools.jackson.databind.node.ObjectNode withQuery(tools.jackson.databind.node.ObjectNode payload, String title) {
        payload.set("sourceQuery", contentQuery(title, null));
        return payload;
    }

    private List<String> header(ImportRecordKind kind) {
        return switch (kind) {
            case DIARY_EVENT -> DIARY_HEADER;
            case REVIEW -> REVIEW_HEADER;
            case WATCHED_MARKER -> WATCHED_HEADER;
            case CURRENT_RATING -> RATING_HEADER;
            case WATCHLIST_ITEM -> WATCHLIST_HEADER;
            default -> throw new BadRequestException("Unsupported Letterboxd CSV record kind");
        };
    }

    private Integer parseYear(String raw, String path, long row) {
        if (raw == null || raw.isBlank()) return null;
        try { int year = Integer.parseInt(raw); if (year < 1 || year > 9999) throw new NumberFormatException(); return year; }
        catch (NumberFormatException ex) { throw failure(path, row, "Year", "Invalid year"); }
    }

    private String date(String raw, String path, long row, String field) {
        if (raw == null || raw.isBlank()) return null;
        try { return LocalDate.parse(raw).toString(); }
        catch (RuntimeException ex) { throw failure(path, row, field, "Invalid local date"); }
    }

    private List<String> splitTags(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        return Arrays.stream(raw.split(",")).map(String::trim).filter(tag -> !tag.isEmpty()).toList();
    }

    private String blankToNull(String value) { return value == null || value.isBlank() ? null : value; }

    private void requireSize(List<String> row, List<String> header, String path, long record) {
        if (row.size() != header.size()) throw failure(path, record, "columns", "Unexpected number of CSV columns");
    }

    private BadRequestException failure(String path, long record, String field, String message) {
        return new BadRequestException(path + " record " + record + " field " + field + ": " + message);
    }

    private enum ListState { VERSION, METADATA_HEADER, METADATA_ROW, ITEMS_HEADER, ITEMS }
}
