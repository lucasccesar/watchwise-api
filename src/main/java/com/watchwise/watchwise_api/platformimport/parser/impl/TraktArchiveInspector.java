package com.watchwise.watchwise_api.platformimport.parser.impl;

import com.watchwise.watchwise_api.common.exception.BadRequestException;
import com.watchwise.watchwise_api.platformimport.model.*;
import com.watchwise.watchwise_api.platformimport.parser.SafeImportReader;
import com.watchwise.watchwise_api.platformimport.service.ImportLimits;
import com.watchwise.watchwise_api.platformimport.storage.ReadableImportArtifact;
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
    private static final Pattern LIST_FILE = Pattern.compile("(?:^|/)lists-list-(\\d+)(?:-sample)?(.*)\\.json$", Pattern.CASE_INSENSITIVE);
    private final SafeImportReader reader;
    private final ObjectMapper mapper;
    private final ImportLimits limits;

    public TraktArchiveInspector(ObjectMapper mapper) { this.reader = null; this.mapper = mapper; this.limits = new ImportLimits(); }
    @Autowired
    public TraktArchiveInspector(SafeImportReader reader, ImportLimits limits, ObjectMapper mapper) {
        this(reader, mapper, limits);
    }
    public TraktArchiveInspector(SafeImportReader reader, ObjectMapper mapper) { this(reader, mapper, new ImportLimits()); }
    public TraktArchiveInspector(SafeImportReader reader, ObjectMapper mapper, ImportLimits limits) {
        this.reader = reader; this.mapper = mapper; this.limits = limits;
    }

    public ImportInventory inspect(ImportReadContext context, ReadableImportArtifact artifact) {
        List<ImportListInventory> lists = new ArrayList<>();
        Map<String, Long> itemCounts = new HashMap<>();
        Map<String, Set<Integer>> pagesByList = new HashMap<>();
        Set<String> presentIds = new HashSet<>();
        long[] history = {0}, ratings = {0}, watched = {0}, watchlist = {0};
        Map<String, Integer> listPages = listPages(reader.entryNames(artifact), limits.maxEntries());
        reader.forEachEntry(artifact, (path, input) -> {
            String file = path.toLowerCase(Locale.ROOT);
            if (file.matches("(?:.*/)?watched-history(?:-\\d+)?\\.json")) history[0] += countArray(input, path);
            else if (file.matches("(?:.*/)?ratings-(?:movies|shows|episodes|seasons)(?:-\\d+)?\\.json")) ratings[0] += countArray(input, path);
            else if (file.matches("(?:.*/)?watched-(?:movies|shows|episodes|seasons)(?:-\\d+)?\\.json")) watched[0] += countArray(input, path);
            else if (file.matches("(?:.*/)?lists-watchlist(?:-\\d+)?\\.json")) watchlist[0] += countArray(input, path);
            else if (file.matches("(?:.*/)?lists-lists(?:-\\d+)?\\.json")) readLists(input, path, lists);
            else if (listPages.containsKey(path)) {
                recordListPage(listId(path), listPages.get(path), input, path, presentIds, pagesByList, itemCounts);
            }
        });
        List<ImportListInventory> withCoverage = lists.stream().map(list -> {
            String coverage = coverage(list, presentIds, pagesByList, itemCounts);
            return new ImportListInventory(list.sourceId(), list.name(), list.description(), list.itemCount(), coverage);
        }).toList();
        return new ImportInventory(List.of(new ImportCategoryInventory(ImportRecordKind.DIARY_EVENT, history[0], history[0] > 0),
                new ImportCategoryInventory(ImportRecordKind.CURRENT_RATING, ratings[0], ratings[0] > 0),
                new ImportCategoryInventory(ImportRecordKind.WATCHED_SUMMARY, watched[0], watched[0] > 0),
                new ImportCategoryInventory(ImportRecordKind.WATCHLIST_ITEM, watchlist[0], watchlist[0] > 0)), withCoverage);
    }

    public ImportInventory inspectFiles(Map<String, InputStream> files) {
        List<ImportListInventory> lists = new ArrayList<>();
        Map<String, Long> itemCounts = new HashMap<>();
        Map<String, Set<Integer>> pagesByList = new HashMap<>();
        Set<String> presentIds = new HashSet<>();
        long historyCount = 0, ratingCount = 0, watchedCount = 0, watchlistCount = 0;
        Map<String, Integer> listPages = listPages(files.keySet(), limits.maxEntries());
        for (var entry : files.entrySet()) {
            String path = entry.getKey();
            String file = path.toLowerCase(Locale.ROOT);
            if (file.matches("(?:.*/)?watched-history(?:-\\d+)?\\.json")) historyCount += countArray(entry.getValue(), path);
            else if (file.matches("(?:.*/)?ratings-(?:movies|shows|episodes|seasons)(?:-\\d+)?\\.json")) ratingCount += countArray(entry.getValue(), path);
            else if (file.matches("(?:.*/)?watched-(?:movies|shows|episodes|seasons)(?:-\\d+)?\\.json")) watchedCount += countArray(entry.getValue(), path);
            else if (file.matches("(?:.*/)?lists-watchlist(?:-\\d+)?\\.json")) watchlistCount += countArray(entry.getValue(), path);
            if (listPages.containsKey(path)) {
                recordListPage(listId(path), listPages.get(path), entry.getValue(), path, presentIds, pagesByList, itemCounts);
                continue;
            }
            if (file.matches("(?:.*/)?lists-lists(?:-\\d+)?\\.json")) readLists(entry.getValue(), path, lists);
        }
        List<ImportListInventory> covered = lists.stream().map(list -> new ImportListInventory(list.sourceId(),
                list.name(), list.description(), list.itemCount(), coverage(list, presentIds, pagesByList, itemCounts))).toList();
        return new ImportInventory(List.of(new ImportCategoryInventory(ImportRecordKind.DIARY_EVENT, historyCount, historyCount > 0),
                new ImportCategoryInventory(ImportRecordKind.CURRENT_RATING, ratingCount, ratingCount > 0),
                new ImportCategoryInventory(ImportRecordKind.WATCHED_SUMMARY, watchedCount, watchedCount > 0),
                new ImportCategoryInventory(ImportRecordKind.WATCHLIST_ITEM, watchlistCount, watchlistCount > 0)), covered);
    }

    public void parseSelectedListMetadata(InputStream input, String sourcePath, ImportReadContext context,
                                          Set<String> selectedListIds,
                                          java.util.function.Consumer<NormalizedImportRecord> sink) {
        forEachArray(input, sourcePath, (node, index) -> {
            String sourceId = requiredId(node.path("ids").path("trakt"), sourcePath, index, "ids.trakt");
            if (!selectedListIds.contains(sourceId)) return;
            String name = requiredText(node.path("name"), sourcePath, index, "name");
            JsonNode count = node.path("item_count");
            if (!count.isIntegralNumber() || !count.canConvertToLong() || count.longValue() < 0) {
                throw new BadRequestException(sourcePath + " record " + index
                        + " field item_count: invalid list metadata");
            }
            var payload = mapper.createObjectNode();
            payload.put("title", name);
            if (node.path("description").isTextual()) payload.put("description", node.path("description").asString());
            payload.put("sourceItemCount", count.longValue());
            payload.put("sourceFile", sourcePath);
            payload.put("sourceRecordNumber", index);
            ImportSourceKey key = new ImportSourceKey(context.sourceNamespace(), "list_metadata", sourceId);
            sink.accept(new NormalizedImportRecord("list_metadata:" + sourceId, ImportRecordKind.LIST_METADATA,
                    key, null, null, payload, Math.toIntExact(index - 1)));
        });
    }

    public static List<String> selectListFiles(Collection<String> paths, String listId) {
        String id;
        try { id = canonical(listId); } catch (RuntimeException ex) { throw new BadRequestException("Invalid Trakt list id"); }
        record Page(int number, String path) {}
        List<Page> pages = new ArrayList<>();
        Map<String, Integer> pageNumbers = listPages(paths, new ImportLimits().maxEntries());
        for (String path : paths) {
            if (pageNumbers.containsKey(path) && listId(path).equals(id)) {
                pages.add(new Page(pageNumbers.get(path), path));
            }
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

    private static String listId(String path) {
        Matcher matcher = LIST_FILE.matcher(path);
        if (!matcher.matches()) throw new IllegalArgumentException("Path is not a Trakt list file");
        return canonical(matcher.group(1));
    }

    private static Map<String, Integer> listPages(Collection<String> paths, int maxPageNumber) {
        record Candidate(String path, int page) {}
        Map<String, List<Candidate>> candidatesBySlug = new HashMap<>();
        Map<String, Integer> pages = new HashMap<>();
        for (String path : paths) {
            Matcher matcher = LIST_FILE.matcher(path);
            if (!matcher.matches()) continue;
            String listId = canonical(matcher.group(1));
            String suffix = matcher.group(2);
            if (suffix.isEmpty()) {
                pages.put(path, 1);
                continue;
            }
            int lastHyphen = suffix.lastIndexOf('-');
            if (lastHyphen < 0 || lastHyphen == suffix.length() - 1
                    || !suffix.substring(lastHyphen + 1).matches("[0-9]+")) {
                pages.put(path, 1);
                continue;
            }
            int page;
            try {
                page = Integer.parseInt(suffix.substring(lastHyphen + 1));
            } catch (NumberFormatException exception) {
                pages.put(path, 1);
                continue;
            }
            if (page == 0) throw new BadRequestException("Invalid Trakt list page number");
            if (page > maxPageNumber) {
                pages.put(path, 1);
                continue;
            }
            String slug = suffix.substring(0, lastHyphen).toLowerCase(Locale.ROOT);
            if (slug.isEmpty()) {
                pages.put(path, page);
                continue;
            }
            String key = listId + "|" + slug;
            candidatesBySlug.computeIfAbsent(key, ignored -> new ArrayList<>()).add(new Candidate(path, page));
            pages.put(path, 1);
        }
        for (List<Candidate> group : candidatesBySlug.values()) {
            if (group.stream().map(Candidate::page).distinct().count() < 2) continue;
            for (Candidate candidate : group) pages.put(candidate.path(), candidate.page());
        }
        return pages;
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
