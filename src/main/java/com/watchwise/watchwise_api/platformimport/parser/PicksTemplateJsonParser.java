package com.watchwise.watchwise_api.platformimport.parser;

import com.watchwise.watchwise_api.common.exception.BadRequestException;
import com.watchwise.watchwise_api.platformimport.model.ImportedTemplateCategory;
import com.watchwise.watchwise_api.platformimport.model.TemplateImportDocument;
import com.watchwise.watchwise_api.platformimport.service.ImportLimits;
import com.watchwise.watchwise_api.pickstemplate.entity.PickAllowedType;
import com.watchwise.watchwise_api.pickstemplate.entity.PickCategoryGroup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonParser;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.net.URI;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@Component
public class PicksTemplateJsonParser {
    private static final long MAX_BYTES = 5L * 1024 * 1024;
    private static final Logger log = LoggerFactory.getLogger(PicksTemplateJsonParser.class);
    private final ImportLimits limits;

    public PicksTemplateJsonParser(ImportLimits limits) { this.limits = limits; }

    public TemplateImportDocument parse(InputStream input) {
        if (input == null) throw invalid("JSON input is required");
        long maxBytes = Math.min(MAX_BYTES, limits.maxTemplateUploadBytes());
        if (maxBytes < 1) throw invalid("JSON byte limit is invalid");
        byte[] bytes;
        try {
            bytes = input.readNBytes(Math.toIntExact(maxBytes + 1));
        } catch (IOException | BadRequestException ex) {
            log.debug("Failed to read Picks template JSON", ex);
            throw invalid("Invalid template JSON");
        }
        try {
            if (bytes.length > maxBytes) throw new MaxUploadSizeExceededException(maxBytes);
            JsonFactory factory = JsonFactory.builder()
                    .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                    .streamReadConstraints(StreamReadConstraints.builder()
                            .maxNestingDepth(limits.maxJsonDepth()).build()).build();
            JsonMapper mapper = JsonMapper.builder(factory)
                    .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).build();
            try (JsonParser parser = factory.createParser(bytes)) {
                JsonNode root = mapper.readTree(parser);
                if (parser.nextToken() != null) throw invalid("Trailing JSON content");
                return document(root);
            }
        } catch (JacksonException ex) {
            log.debug("Rejected malformed Picks template JSON", ex);
            throw invalid("Invalid template JSON");
        }
    }

    private TemplateImportDocument document(JsonNode root) {
        requireObject(root, "root", Set.of("schemaVersion", "title", "description", "coverImage", "instructions", "eligibility", "categories"));
        JsonNode version = root.get("schemaVersion");
        if (version != null && (!version.isNumber() || version.decimalValue().compareTo(BigDecimal.ONE) != 0))
            throw invalid("Unsupported schemaVersion");
        String title = requiredText(root, "title", 120);
        String description = optionalText(root, "description", Integer.MAX_VALUE);
        String instructions = optionalText(root, "instructions", Integer.MAX_VALUE);
        String cover = optionalText(root, "coverImage", 2048);
        if (cover != null) validateUrl(cover);
        LocalDate start = null, end = null;
        if (root.has("eligibility")) {
            JsonNode eligibility = root.get("eligibility");
            requireObject(eligibility, "eligibility", Set.of("start", "end"));
            if (!eligibility.has("start") || !eligibility.has("end")) throw invalid("eligibility requires start and end");
            start = date(eligibility, "start");
            end = date(eligibility, "end");
            if (start.isAfter(end)) throw invalid("eligibility start must precede end");
        }
        JsonNode sourceCategories = root.get("categories");
        if (sourceCategories == null || !sourceCategories.isArray() || sourceCategories.isEmpty()
                || sourceCategories.size() > 100) throw invalid("categories must contain 1 to 100 entries");
        List<ImportedTemplateCategory> categories = new ArrayList<>();
        int total = 0;
        for (JsonNode node : sourceCategories) {
            int order = categories.size() + 1;
            ImportedTemplateCategory category = category(node, order);
            total += category.nominees().size();
            if (total > 5000) throw invalid("Too many nominees in template");
            categories.add(category);
        }
        return new TemplateImportDocument(1, title, description, cover, instructions, start, end, List.copyOf(categories));
    }

    private ImportedTemplateCategory category(JsonNode node, int order) {
        requireObject(node, "category", Set.of("name", "description", "type", "group", "nominees"));
        String name = requiredText(node, "name", 120);
        String description = optionalText(node, "description", Integer.MAX_VALUE);
        PickAllowedType type = enumValue(node, "type", PickAllowedType.class, null);
        PickCategoryGroup group = enumValue(node, "group", PickCategoryGroup.class, PickCategoryGroup.PRIMARY);
        boolean present = node.has("nominees");
        List<JsonNode> nominees = new ArrayList<>();
        if (present) {
            JsonNode array = node.get("nominees");
            if (!array.isArray() || array.isEmpty() || array.size() > 500)
                throw invalid("nominees must be a nonempty array of at most 500 entries or be omitted");
            for (JsonNode nominee : array) {
                validateNominee(nominee, type);
                nominees.add(nominee);
            }
        }
        return new ImportedTemplateCategory(name, description, type, group, order, List.copyOf(nominees), present);
    }

    private void validateNominee(JsonNode node, PickAllowedType type) {
        if (node.isTextual()) {
            if (isEcmaBlank(node.asString())) throw invalid("nominee text is blank");
            return;
        }
        switch (type) {
            case MOVIE, SERIES -> {
                requireObject(node, "nominee", Set.of("tmdbId", "title", "releaseYear"));
                String id = optionalId(node, "tmdbId");
                String title = optionalNonblank(node, "title");
                Integer year = optionalInteger(node, "releaseYear", 1);
                if (year != null && year > 9999) throw invalid("Invalid releaseYear");
                if (id == null && (title == null || year == null))
                    throw invalid("media nominee requires tmdbId or title and releaseYear");
                if ((title == null) != (year == null) && id == null)
                    throw invalid("title and releaseYear must appear together");
            }
            case EPISODE -> {
                requireObject(node, "nominee", Set.of("seriesTmdbId", "seasonNumber", "episodeNumber"));
                requiredId(node, "seriesTmdbId");
                requiredInteger(node, "seasonNumber", 0);
                requiredInteger(node, "episodeNumber", 1);
            }
            case PERSON -> {
                requireObject(node, "nominee", Set.of("personTmdbId", "name", "contextContent"));
                String id = optionalId(node, "personTmdbId");
                String name = optionalNonblank(node, "name");
                if (id == null && name == null) throw invalid("person nominee requires personTmdbId or name");
                if (node.has("contextContent")) validateContext(node.get("contextContent"));
            }
        }
    }

    private void validateContext(JsonNode node) {
        requireObject(node, "contextContent", Set.of("type", "tmdbId", "seriesTmdbId", "seasonNumber", "episodeNumber"));
        PickAllowedType type = enumValue(node, "type", PickAllowedType.class, null);
        switch (type) {
            case MOVIE, SERIES -> {
                requiredId(node, "tmdbId");
                if (node.has("seriesTmdbId") || node.has("seasonNumber") || node.has("episodeNumber"))
                    throw invalid("Invalid contextContent fields");
            }
            case EPISODE -> {
                requiredId(node, "seriesTmdbId");
                requiredInteger(node, "seasonNumber", 0);
                requiredInteger(node, "episodeNumber", 1);
                if (node.has("tmdbId")) throw invalid("Episode context cannot use tmdbId");
            }
            case PERSON -> throw invalid("contextContent must reference content");
        }
    }

    private static void requireObject(JsonNode node, String path, Set<String> allowed) {
        if (node == null || !node.isObject()) throw invalid(path + " must be an object");
        for (var entry : node.properties()) if (!allowed.contains(entry.getKey()))
            throw invalid("Unknown " + path + " property: " + entry.getKey());
    }

    private static String requiredText(JsonNode node, String key, int max) {
        String value = optionalText(node, key, max);
        if (value == null || isEcmaBlank(value)) throw invalid(key + " is required");
        return value;
    }

    private static String optionalText(JsonNode node, String key, int max) {
        JsonNode value = node.get(key);
        if (value == null) return null;
        if (!value.isTextual() || value.asString().codePointCount(0, value.asString().length()) > max)
            throw invalid("Invalid " + key);
        return value.asString();
    }

    private static String optionalNonblank(JsonNode node, String key) {
        String value = optionalText(node, key, Integer.MAX_VALUE);
        if (value != null && isEcmaBlank(value)) throw invalid("Invalid " + key);
        return value;
    }

    private static String requiredId(JsonNode node, String key) {
        String value = optionalId(node, key);
        if (value == null) throw invalid(key + " is required");
        return value;
    }

    private static String optionalId(JsonNode node, String key) {
        String value = optionalText(node, key, Integer.MAX_VALUE);
        if (value != null && !value.matches("[0-9]+")) throw invalid("Invalid " + key);
        return value;
    }

    private static int requiredInteger(JsonNode node, String key, int min) {
        Integer value = optionalInteger(node, key, min);
        if (value == null) throw invalid(key + " is required");
        return value;
    }

    private static Integer optionalInteger(JsonNode node, String key, int min) {
        JsonNode value = node.get(key);
        if (value == null) return null;
        if (!value.isNumber()) throw invalid("Invalid " + key);
        try {
            int exact = value.decimalValue().intValueExact();
            if (exact < min) throw invalid("Invalid " + key);
            return exact;
        } catch (ArithmeticException ex) {
            throw invalid("Invalid " + key);
        }
    }

    private static <E extends Enum<E>> E enumValue(JsonNode node, String key, Class<E> type, E fallback) {
        return TemplateImportEnumValues.enumValue(node, key, type, fallback);
    }

    private static LocalDate date(JsonNode node, String key) {
        String value = requiredText(node, key, 10);
        if (!value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) throw invalid("Invalid " + key);
        try { return LocalDate.parse(value); }
        catch (DateTimeParseException ex) { throw invalid("Invalid " + key); }
    }

    private static void validateUrl(String value) {
        try {
            URI uri = URI.create(value);
            if (!Set.of("http", "https").contains(uri.getScheme()) || uri.getHost() == null
                    || uri.getUserInfo() != null || value.isBlank()) throw invalid("Invalid coverImage URL");
        } catch (IllegalArgumentException ex) { throw invalid("Invalid coverImage URL"); }
    }

    private static BadRequestException invalid(String message) { return new BadRequestException(message); }

    private static boolean isEcmaBlank(String value) {
        return TemplateImportEnumValues.isEcmaBlank(value);
    }
}
