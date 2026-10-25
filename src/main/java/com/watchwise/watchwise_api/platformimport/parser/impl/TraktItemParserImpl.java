package com.watchwise.watchwise_api.platformimport.parser.impl;

import com.watchwise.watchwise_api.content.dto.MediaReference;
import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.platformimport.model.*;
import com.watchwise.watchwise_api.platformimport.parser.TraktItemParser;
import com.watchwise.watchwise_api.platformimport.service.ImportLimits;
import com.watchwise.watchwise_api.common.exception.BadRequestException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import java.util.function.Consumer;

@Component
public class TraktItemParserImpl implements TraktItemParser {
    private final ObjectMapper mapper;
    private final ImportLimits limits;
    public TraktItemParserImpl(ObjectMapper mapper) { this(mapper, new ImportLimits()); }
    @Autowired
    public TraktItemParserImpl(ObjectMapper mapper, ImportLimits limits) { this.mapper = mapper; this.limits = limits; }

    @Override
    public void parse(InputStream json, ImportReadContext context, String sourcePath, ImportRecordKind kind,
                      Consumer<NormalizedImportRecord> sink) {
        try (JsonParser parser = mapper.createParser(json)) {
            if (parser.nextToken() != JsonToken.START_ARRAY) throw failure(sourcePath, 0, "root", "Expected JSON array");
            long number = 0;
            while (parser.nextToken() != JsonToken.END_ARRAY) {
                if (parser.currentToken() == null) throw failure(sourcePath, number + 1, "root", "Unterminated JSON array");
                number++;
                if (number > limits.maxRecords()) throw failure(sourcePath, number, "record", "JSON record limit exceeded");
                JsonNode node = mapper.readerFor(JsonNode.class).without(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readValue(parser);
                if (!node.isObject()) throw failure(sourcePath, number, "record", "Expected object");
                validateDepth(node, sourcePath, number);
                NormalizedImportRecord record = normalize(node, context, sourcePath, kind, number);
                if (record != null) sink.accept(record);
            }
            if (parser.nextToken() != null) throw failure(sourcePath, number, "root", "Unexpected trailing JSON");
        } catch (BadRequestException ex) { throw ex; }
        catch (JacksonException ex) { throw failure(sourcePath, 0, "json", "Malformed JSON array: " + ex.getMessage()); }
    }

    private NormalizedImportRecord normalize(JsonNode row, ImportReadContext context, String path,
                                             ImportRecordKind kind, long recordNumber) {
        String type = text(row, "type");
        String branch = type == null ? inferBranch(row, kind) : type.toLowerCase(Locale.ROOT);
        if (branch.equals("person") || branch.equals("comment") || branch.equals("collection")
                || branch.equals("hidden") || branch.equals("network") || branch.equals("_errors")) return null;
        if (!Set.of("movie", "show", "season", "episode").contains(branch))
            throw failure(path, recordNumber, "type", "Unsupported Trakt record type");
        JsonNode media = row.path(branch);
        if (!media.isObject()) throw failure(path, recordNumber, branch, "Missing media identity object for declared type");
        boolean episode = branch.equals("episode") || branch.equals("season");
        JsonNode parent = episode ? row.path("show") : media;
        if (!parent.isObject()) throw failure(path, recordNumber, branch, "Missing media identity object");
        String title = requiredText(parent, "title", path, recordNumber, branch + ".title");
        if (title.length() > 500) throw failure(path, recordNumber, branch + ".title", "Title is too long");
        Integer year = optionalPositiveInt(parent, "year", path, recordNumber, branch + ".year");
        String tmdb = optionalId(parent.path("ids").path("tmdb"), path, recordNumber, branch + ".ids.tmdb");
        ContentType contentType = episode ? ContentType.SERIES : branch.equals("movie") ? ContentType.MOVIE : ContentType.SERIES;
        MediaReference ref = null;
        Integer season = null, episodeNumber = null;
        if (episode) {
            JsonNode child = row.path(branch);
            season = branch.equals("season")
                    ? requiredNonNegativeInt(child, "number", path, recordNumber, "season.number")
                    : requiredNonNegativeInt(child, "season", path, recordNumber, "episode.season");
            if (branch.equals("episode")) episodeNumber = requiredPositiveInt(child, "number", path, recordNumber, branch + ".number");
            if (tmdb != null) ref = branch.equals("season") ? MediaReference.season(tmdb, season)
                    : MediaReference.episode(tmdb, season, episodeNumber);
        } else if (tmdb != null) ref = contentType == ContentType.MOVIE ? MediaReference.movie(tmdb) : MediaReference.series(tmdb);

        String id = optionalId(row.path("id"), path, recordNumber, "id");
        if (id == null) {
            String traktId = optionalId(media.path("ids").path("trakt"), path, recordNumber, branch + ".ids.trakt");
            String date = text(row, "watched_at");
            id = (traktId == null ? "tmdb-" + (tmdb == null ? title : tmdb) : traktId) + "-" + (date == null ? recordNumber : date);
        }
        var payload = mapper.createObjectNode();
        payload.put("title", title);
        if (year != null) payload.put("releaseYear", year);
        copyText(row, payload, "uri", "sourceContentUri");
        copyDate(row, payload, "watched_at", "watchedOn", context.timeZone(), path, recordNumber);
        copyText(row, payload, "watched_at", "sourceWatchedAt");
        if (kind == ImportRecordKind.WATCHED_SUMMARY) {
            copyPositiveCount(row, payload, "plays", "declaredPlays", path, recordNumber);
            copyText(row, payload, "last_watched_at", "sourceLastWatchedAt");
            copyDate(row, payload, "last_watched_at", "declaredLastWatchedOn", context.timeZone(), path, recordNumber);
            copyNonNegativeCount(media, payload, "aired_episodes", "airedEpisodeCount", path, recordNumber);
        }
        copyDate(row, payload, "rated_at", "sourceRecordedOn", context.timeZone(), path, recordNumber);
        copyInstant(row, payload, "rated_at", "sourcePublishedAt", path, recordNumber);
        if (!payload.has("sourceRecordedOn")) copyDate(row, payload, "listed_at", "sourceRecordedOn", context.timeZone(), path, recordNumber);
        JsonNode score = row.path("rating");
        if (!score.isMissingNode() && !score.isNull()) {
            if (!score.isIntegralNumber() || score.intValue() < 1 || score.intValue() > 10)
                throw failure(path, recordNumber, "rating", "Rating must be an integer from 1 to 10");
            payload.put("score", score.intValue());
        }
        copyText(row, payload, "review", "review");
        if (row.path("rewatched").isBoolean()) payload.put("sourceRewatch", row.path("rewatched").booleanValue());
        JsonNode tags = row.path("tags"); if (tags.isArray()) payload.set("tags", tags.deepCopy());
        Integer rank = null;
        if (kind == ImportRecordKind.LIST_ITEM || kind == ImportRecordKind.WATCHLIST_ITEM) {
            rank = optionalPositiveInt(row, "rank", path, recordNumber, "rank");
            if (rank != null) {
                if (rank > limits.maxRecords()) throw failure(path, recordNumber, "rank", "Rank exceeds the item limit");
                payload.put("rank", rank);
            }
        } else if (row.path("rank").canConvertToInt()) {
            payload.put("rank", row.path("rank").intValue());
        }
        if (kind == ImportRecordKind.LIST_ITEM && text(row, "notes") != null) {
            copyText(row, payload, "notes", "description");
        } else {
            copyText(row, payload, "description", "description");
        }
        payload.put("sourceFile", path);
        payload.put("sourceRecordNumber", recordNumber);
        if (ref == null) {
            var query = payload.putObject("sourceQuery").put("kind", "CONTENT").put("title", title).put("contentType", contentType.name());
            if (year != null) query.put("year", year);
            if (episode) { query.put("seasonNumber", season); if (episodeNumber != null) query.put("episodeNumber", episodeNumber); }
        }
        String kindId = kind == ImportRecordKind.LIST_ITEM ? "list_item" : kind.name().toLowerCase(Locale.ROOT);
        int ordinal = rank == null ? Math.toIntExact(recordNumber - 1) : rank - 1;
        return new NormalizedImportRecord(kind.name().toLowerCase(Locale.ROOT) + ":" + id, kind,
                new ImportSourceKey(context.sourceNamespace(), kindId, id), null,
                ref == null ? null : new ImportTarget.ContentTarget(ref), payload, ordinal);
    }

    private String inferBranch(JsonNode row, ImportRecordKind kind) {
        if (row.path("episode").isObject()) return "episode";
        if (row.path("season").isObject()) return "season";
        if (row.path("show").isObject()) return "show";
        return "movie";
    }
    private String requiredText(JsonNode node, String field, String path, long record, String label) {
        JsonNode value = node.path(field); if (!value.isTextual() || value.asString().isBlank()) throw failure(path, record, label, "Required text is invalid"); return value.asString();
    }
    private String text(JsonNode node, String field) { JsonNode value = node.path(field); return value.isTextual() ? value.asString() : null; }
    private String optionalId(JsonNode node, String path, long record, String field) {
        if (node.isMissingNode() || node.isNull()) return null;
        String value;
        if (node.isIntegralNumber()) value = node.toString();
        else if (node.isTextual()) value = node.asString();
        else throw failure(path, record, field, "Id must be a number or digit string");
        if (!value.matches("[0-9]+") || value.chars().allMatch(c -> c == '0')) throw failure(path, record, field, "Id must contain positive digits");
        return new java.math.BigInteger(value).toString();
    }
    private Integer requiredNonNegativeInt(JsonNode node, String field, String path, long record, String label) {
        JsonNode v = node.path(field); if (!v.isIntegralNumber() || !v.canConvertToInt() || v.intValue() < 0) throw failure(path, record, label, "Expected non-negative integer"); return v.intValue();
    }
    private Integer requiredPositiveInt(JsonNode node, String field, String path, long record, String label) {
        Integer value = requiredNonNegativeInt(node, field, path, record, label); if (value < 1) throw failure(path, record, label, "Expected positive integer"); return value;
    }
    private Integer optionalPositiveInt(JsonNode node, String field, String path, long record, String label) {
        JsonNode v = node.path(field); if (v.isMissingNode() || v.isNull()) return null; if (!v.isIntegralNumber() || !v.canConvertToInt() || v.intValue() < 1) throw failure(path, record, label, "Expected positive integer"); return v.intValue();
    }
    private void copyDate(JsonNode from, tools.jackson.databind.node.ObjectNode to, String source, String target,
                          ZoneId zone, String path, long record) {
        String value = text(from, source); if (value == null) return;
        try { to.put(target, LocalDate.ofInstant(Instant.parse(value), zone).toString()); }
        catch (RuntimeException ex) { throw failure(path, record, source, "Invalid timestamp"); }
    }
    private void copyInstant(JsonNode from, tools.jackson.databind.node.ObjectNode to, String source, String target,
                             String path, long record) {
        String value = text(from, source); if (value == null) return;
        try { to.put(target, Instant.parse(value).toString()); }
        catch (RuntimeException ex) { throw failure(path, record, source, "Invalid timestamp"); }
    }
    private void copyText(JsonNode from, tools.jackson.databind.node.ObjectNode to, String source, String target) {
        String value = text(from, source); if (value != null) to.put(target, value);
    }
    private void copyPositiveCount(JsonNode from, tools.jackson.databind.node.ObjectNode to, String source,
                                   String target, String path, long record) {
        JsonNode value = from.path(source);
        if (value.isMissingNode() || value.isNull()) return;
        if (!value.isIntegralNumber() || !value.canConvertToInt() || value.intValue() < 1)
            throw failure(path, record, source, "Expected positive integer");
        to.put(target, value.intValue());
    }
    private void copyNonNegativeCount(JsonNode from, tools.jackson.databind.node.ObjectNode to, String source,
                                      String target, String path, long record) {
        JsonNode value = from.path(source);
        if (value.isMissingNode() || value.isNull()) return;
        if (!value.isIntegralNumber() || !value.canConvertToInt() || value.intValue() < 0)
            throw failure(path, record, source, "Expected non-negative integer");
        to.put(target, value.intValue());
    }
    private BadRequestException failure(String path, long record, String field, String message) {
        return new BadRequestException(path + " record " + record + " field " + field + ": " + message);
    }

    private void validateDepth(JsonNode root, String path, long record) {
        record Frame(JsonNode node, int depth) {}
        Deque<Frame> pending = new ArrayDeque<>(); pending.push(new Frame(root, 1));
        while (!pending.isEmpty()) {
            Frame frame = pending.pop();
            if (frame.depth() > limits.maxJsonDepth()) throw failure(path, record, "json", "JSON nesting depth exceeded");
            if (frame.node().isObject() || frame.node().isArray()) frame.node().forEach(child -> pending.push(new Frame(child, frame.depth() + 1)));
        }
    }
}
