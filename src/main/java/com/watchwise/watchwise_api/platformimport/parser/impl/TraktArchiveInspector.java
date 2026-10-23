package com.watchwise.watchwise_api.platformimport.parser.impl;

import com.watchwise.watchwise_api.common.exception.BadRequestException;
import com.watchwise.watchwise_api.platformimport.model.*;
import com.watchwise.watchwise_api.platformimport.parser.SafeImportReader;
import com.watchwise.watchwise_api.platformimport.service.ImportLimits;
import com.watchwise.watchwise_api.platformimport.storage.PrivateImportFileStore;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class TraktArchiveInspector {
    private static final Pattern LIST_PAGE = Pattern.compile("(?:^|/)lists-list-(\\d+)(?:-sample)?-(\\d+)\\.json$", Pattern.CASE_INSENSITIVE);
    private static final Pattern LIST_WHOLE = Pattern.compile("(?:^|/)lists-list-(\\d+)(?:-sample)?\\.json$", Pattern.CASE_INSENSITIVE);
    private final SafeImportReader reader;
    private final ObjectMapper mapper;
    private final ImportLimits limits;

    public TraktArchiveInspector(ObjectMapper mapper) { this.reader = null; this.mapper = mapper; this.limits = new ImportLimits(); }
    @Autowired
    public TraktArchiveInspector(PrivateImportFileStore store, ImportLimits limits, ObjectMapper mapper) {
        this(new SafeImportReader(store, limits), mapper, limits);
    }
    public TraktArchiveInspector(SafeImportReader reader, ObjectMapper mapper) { this(reader, mapper, new ImportLimits()); }
    public TraktArchiveInspector(SafeImportReader reader, ObjectMapper mapper, ImportLimits limits) {
        this.reader = reader; this.mapper = mapper; this.limits = limits;
    }

    public ImportInventory inspect(ImportReadContext context) {
        List<ImportListInventory> lists = new ArrayList<>();
        Map<String, Long> itemCounts = new HashMap<>();
        Map<String, Set<Integer>> pagesByList = new HashMap<>();
        Set<String> presentIds = new HashSet<>();
        long[] history = {0}, ratings = {0}, watched = {0};
        reader.forEachEntry(context.storageKey(), (path, input) -> {
            String file = path.toLowerCase(Locale.ROOT);
            if (file.matches("(?:.*/)?watched-history(?:-\\d+)?\\.json")) history[0] += countArray(input, path);
            else if (file.matches("(?:.*/)?ratings-(?:movies|shows|episodes|seasons)(?:-\\d+)?\\.json")) ratings[0] += countArray(input, path);
            else if (file.matches("(?:.*/)?watched-(?:movies|shows|episodes|seasons)(?:-\\d+)?\\.json")) watched[0] += countArray(input, path);
            else if (file.matches("(?:.*/)?lists-lists(?:-\\d+)?\\.json")) readLists(input, path, lists);
            else {
                Matcher page = LIST_PAGE.matcher(path);
                Matcher whole = LIST_WHOLE.matcher(path);
                if (page.matches()) {
                    recordListPage(id(page), pageNumber(page), input, path, presentIds, pagesByList, itemCounts);
                } else if (whole.matches()) {
                    recordListPage(canonical(whole.group(1)), 1, input, path, presentIds, pagesByList, itemCounts);
                }
            }
        });
        List<ImportListInventory> withCoverage = lists.stream().map(list -> {
            String coverage = coverage(list, presentIds, pagesByList, itemCounts);
            return new ImportListInventory(list.sourceId(), list.name(), list.description(), list.itemCount(), coverage);
        }).toList();
        return new ImportInventory(List.of(new ImportCategoryInventory(ImportRecordKind.DIARY_EVENT, history[0], history[0] > 0),
                new ImportCategoryInventory(ImportRecordKind.CURRENT_RATING, ratings[0], ratings[0] > 0),
                new ImportCategoryInventory(ImportRecordKind.WATCHED_SUMMARY, watched[0], watched[0] > 0)), withCoverage);
    }

    public ImportInventory inspectFiles(Map<String, InputStream> files) {
        List<ImportListInventory> lists = new ArrayList<>();
        Map<String, Long> itemCounts = new HashMap<>();
        Map<String, Set<Integer>> pagesByList = new HashMap<>();
        Set<String> presentIds = new HashSet<>();
        long historyCount = 0, ratingCount = 0, watchedCount = 0;
        for (var entry : files.entrySet()) {
            String path = entry.getKey();
            String file = path.toLowerCase(Locale.ROOT);
            if (file.matches("(?:.*/)?watched-history(?:-\\d+)?\\.json")) historyCount += countArray(entry.getValue(), path);
            else if (file.matches("(?:.*/)?ratings-(?:movies|shows|episodes|seasons)(?:-\\d+)?\\.json")) ratingCount += countArray(entry.getValue(), path);
            else if (file.matches("(?:.*/)?watched-(?:movies|shows|episodes|seasons)(?:-\\d+)?\\.json")) watchedCount += countArray(entry.getValue(), path);
            Matcher page = LIST_PAGE.matcher(path);
            if (page.matches()) {
                recordListPage(id(page), pageNumber(page), entry.getValue(), path, presentIds, pagesByList, itemCounts);
                continue;
            }
            Matcher whole = LIST_WHOLE.matcher(path);
            if (whole.matches()) {
                recordListPage(canonical(whole.group(1)), 1, entry.getValue(), path, presentIds, pagesByList, itemCounts);
                continue;
            }
            if (file.matches("(?:.*/)?lists-lists(?:-\\d+)?\\.json")) readLists(entry.getValue(), path, lists);
        }
        List<ImportListInventory> covered = lists.stream().map(list -> new ImportListInventory(list.sourceId(),
                list.name(), list.description(), list.itemCount(), coverage(list, presentIds, pagesByList, itemCounts))).toList();
        return new ImportInventory(List.of(new ImportCategoryInventory(ImportRecordKind.DIARY_EVENT, historyCount, historyCount > 0),
                new ImportCategoryInventory(ImportRecordKind.CURRENT_RATING, ratingCount, ratingCount > 0),
                new ImportCategoryInventory(ImportRecordKind.WATCHED_SUMMARY, watchedCount, watchedCount > 0)), covered);
    }

    public static List<String> selectListFiles(Collection<String> paths, String listId) {
        String id;
        try { id = canonical(listId); } catch (RuntimeException ex) { throw new BadRequestException("Invalid Trakt list id"); }
        record Page(int number, String path) {}
        List<Page> pages = new ArrayList<>();
        for (String path : paths) {
            Matcher paged = LIST_PAGE.matcher(path);
            Matcher whole = LIST_WHOLE.matcher(path);
            if (paged.matches() && canonical(paged.group(1)).equals(id)) {
                try {
                    int page = Integer.parseInt(paged.group(2));
                    if (page < 1) throw new NumberFormatException();
                    pages.add(new Page(page, path));
                }
                catch (NumberFormatException ex) { throw new BadRequestException("Invalid Trakt list page number"); }
            } else if (whole.matches() && canonical(whole.group(1)).equals(id)) pages.add(new Page(1, path));
        }
        pages.sort(Comparator.comparingInt(Page::number).thenComparing(Page::path));
        return pages.stream().map(Page::path).toList();
    }

    private void readLists(InputStream input, String sourcePath, List<ImportListInventory> output) {
        Set<String> ids = new HashSet<>();
        forEachArray(input, sourcePath, (node, index) -> {
            String id = requiredId(node.path("ids").path("trakt"), sourcePath, index, "ids.trakt");
            if (!ids.add(id)) throw new BadRequestException(sourcePath + " record " + index + " field ids.trakt: duplicate list id");
            String name = requiredText(node.path("name"), sourcePath, index, "name");
            JsonNode count = node.path("item_count");
            if (!count.isIntegralNumber() || !count.canConvertToLong() || count.longValue() < 0)
                throw new BadRequestException(sourcePath + " record " + index + " field item_count: invalid list metadata");
            String description = node.path("description").isTextual() ? node.path("description").asString() : null;
            output.add(new ImportListInventory(id, name, description, count.longValue(), null));
        });
    }

    private void recordListPage(String id, int page, InputStream input, String path, Set<String> presentIds,
                                Map<String, Set<Integer>> pagesByList, Map<String, Long> itemCounts) {
        presentIds.add(id);
        boolean firstOccurrence = pagesByList.computeIfAbsent(id, ignored -> new HashSet<>()).add(page);
        long count = countArray(input, path);
        if (firstOccurrence) itemCounts.merge(id, count, Long::sum);
    }

    private String coverage(ImportListInventory list, Set<String> presentIds,
                            Map<String, Set<Integer>> pagesByList, Map<String, Long> itemCounts) {
        if (list.itemCount() == 0) return "COMPLETE";
        Set<Integer> pages = pagesByList.getOrDefault(list.sourceId(), Set.of());
        return presentIds.contains(list.sourceId()) && itemCounts.getOrDefault(list.sourceId(), 0L) >= list.itemCount()
                && hasContiguousPages(pages) ? "COMPLETE" : "INCOMPLETE";
    }

    private boolean hasContiguousPages(Set<Integer> pages) {
        int expected = 1;
        for (int page : pages.stream().sorted().toList()) {
            if (page != expected) return false;
            expected++;
        }
        return !pages.isEmpty();
    }

    private String id(Matcher page) { return canonical(page.group(1)); }

    private int pageNumber(Matcher page) {
        try {
            int number = Integer.parseInt(page.group(2));
            if (number < 1) throw new NumberFormatException();
            return number;
        } catch (NumberFormatException ex) {
            throw new BadRequestException("Invalid Trakt list page number");
        }
    }
    private long countArray(InputStream input, String path) { long[] count = {0}; forEachArray(input, path, (n, i) -> count[0]++); return count[0]; }
    private void forEachArray(InputStream input, String path, java.util.function.BiConsumer<JsonNode, Long> consumer) {
        try (JsonParser parser = mapper.createParser(input)) {
            if (parser.nextToken() != JsonToken.START_ARRAY) throw new BadRequestException(path + " record 0 field root: expected array");
            long index = 0;
            while (parser.nextToken() != JsonToken.END_ARRAY) {
                index++;
                if (index > limits.maxRecords()) throw new BadRequestException(path + " record " + index + " field record: JSON record limit exceeded");
                JsonNode node = mapper.readerFor(JsonNode.class).without(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readValue(parser);
                if (!node.isObject()) throw new BadRequestException(path + " record " + index + " field record: expected object");
                consumer.accept(node, index);
            }
        } catch (BadRequestException ex) { throw ex; }
        catch (JacksonException ex) { throw new BadRequestException(path + " record 0 field json: malformed JSON: " + ex.getMessage()); }
    }
    private String requiredId(JsonNode value, String path, long record, String field) {
        if (!value.isIntegralNumber() && !value.isTextual()) throw new BadRequestException(path + " record " + record + " field " + field + ": invalid id");
        String id = value.isTextual() ? value.asString() : value.toString();
        if (!id.matches("[0-9]+") || id.chars().allMatch(c -> c == '0')) throw new BadRequestException(path + " record " + record + " field " + field + ": invalid id");
        return new java.math.BigInteger(id).toString();
    }
    private String requiredText(JsonNode value, String path, long record, String field) {
        if (!value.isTextual() || value.asString().isBlank()) throw new BadRequestException(path + " record " + record + " field " + field + ": required text");
        return value.asString();
    }
    private static String canonical(String value) { return new java.math.BigInteger(value).toString(); }
}
