package com.watchwise.watchwise_api.platformimport.parser;

import com.watchwise.watchwise_api.content.dto.MediaReference;
import com.watchwise.watchwise_api.platformimport.model.*;
import com.watchwise.watchwise_api.platformimport.parser.impl.TraktArchiveInspector;
import com.watchwise.watchwise_api.platformimport.parser.impl.TraktHistoryParser;
import com.watchwise.watchwise_api.platformimport.parser.impl.TraktRatingsParser;
import com.watchwise.watchwise_api.platformimport.parser.impl.TraktItemParserImpl;
import com.watchwise.watchwise_api.platformimport.parser.SafeImportReader;
import com.watchwise.watchwise_api.platformimport.service.ImportLimits;
import com.watchwise.watchwise_api.platformimport.storage.ImportArtifactReader;
import com.watchwise.watchwise_api.platformimport.storage.ReadableImportArtifact;
import com.watchwise.watchwise_api.platformimport.storage.R2StoreFixture;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TraktExportParserTest {
    @TempDir Path tempDir;
    private static final String ROOT = "/import/trakt/";
    private static final ImportReadContext CONTEXT = new ImportReadContext(UUID.fromString("00000000-0000-0000-0000-000000000001"),
            UUID.fromString("00000000-0000-0000-0000-000000000002"), "fixture", "trakt", ZoneId.of("UTC"),
            0, "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
    private final ObjectMapper mapper = new ObjectMapper();
    private final TraktHistoryParser history = new TraktHistoryParser(mapper);
    private final TraktRatingsParser ratings = new TraktRatingsParser(mapper);

    @Test
    void parsesHistoryPartsAndUsesShowTmdbIdForEpisodeRatherThanEpisodeId() throws Exception {
        List<NormalizedImportRecord> records = new ArrayList<>();
        parse(history, "watched-history-1.json", records);
        parse(history, "watched-history-2.json", records);

        assertThat(records).hasSize(4);
        assertThat(((ImportTarget.ContentTarget) records.getFirst().target()).content())
                .isEqualTo(MediaReference.episode("123", 1, 2));
        assertThat(records).extracting(r -> r.sourceKey().sourceId()).containsExactly("9001", "9002", "9001", "9003");
        assertThat(records.get(3).target()).isInstanceOf(ImportTarget.ContentTarget.class);
    }

    @Test
    void parsesRatingsAndKeepsRatedTimeDistinctFromWatchedTime() throws Exception {
        List<NormalizedImportRecord> records = new ArrayList<>();
        try (InputStream input = resource("ratings-shows.json")) {
            ratings.parse(input, CONTEXT, "ratings/ratings-shows.json", records::add);
        }
        assertThat(records).hasSize(1);
        assertThat(records.getFirst().payload().path("sourceRecordedOn").asString()).isEqualTo("2025-06-04");
        assertThat(records.getFirst().payload().path("watchedOn").isMissingNode()).isTrue();
        assertThat(records.getFirst().payload().path("sourceWatchedAt").isMissingNode()).isTrue();
        assertThat(records.getFirst().payload().path("score").asInt()).isEqualTo(8);
        assertThat(((ImportTarget.ContentTarget) records.getFirst().target()).content()).isEqualTo(MediaReference.series("123"));
    }

    @Test
    void preservesExactRatingPublicationInstantAndUserZoneDate() {
        var context = new ImportReadContext(CONTEXT.ownerId(), CONTEXT.jobId(), CONTEXT.storageKey(),
                CONTEXT.sourceNamespace(), ZoneId.of("America/Los_Angeles"), CONTEXT.expectedBytes(),
                CONTEXT.expectedSha256());
        String json = "[{\"rating\":8,\"rated_at\":\"2025-06-04T03:30:00Z\","
                + "\"show\":{\"title\":\"Example\",\"year\":2020,\"ids\":{\"tmdb\":123}}}]";
        List<NormalizedImportRecord> records = new ArrayList<>();

        ratings.parse(stream(json), context, "ratings/ratings-shows.json", records::add);

        assertThat(records).hasSize(1);
        assertThat(records.getFirst().payload().path("sourcePublishedAt").asString())
                .isEqualTo("2025-06-04T03:30:00Z");
        assertThat(records.getFirst().payload().path("sourceRecordedOn").asString())
                .isEqualTo("2025-06-03");
    }

    @Test
    void mapsTraktListNotesToTheListItemDescription() {
        TraktItemParserImpl items = new TraktItemParserImpl(mapper);
        String json = """
                [{"id":41,"type":"movie","rank":1,"notes":"Watch the director's cut","movie":{"title":"Example","year":2001,"ids":{"tmdb":51001}}}]
                """;
        List<NormalizedImportRecord> records = new ArrayList<>();

        items.parse(stream(json), CONTEXT, "lists/list-41.json", ImportRecordKind.LIST_ITEM, records::add);

        assertThat(records).hasSize(1);
        assertThat(records.getFirst().payload().path("description").asString())
                .isEqualTo("Watch the director's cut");
    }

    @Test
    void preservesCheckpointOrdinalAndRetainsTraktRankWhenSourceRowsAreOutOfOrder() {
        TraktItemParserImpl items = new TraktItemParserImpl(mapper);
        String json = "[{\"id\":42,\"type\":\"movie\",\"rank\":2,\"movie\":{\"title\":\"B\",\"ids\":{\"tmdb\":51002}}},"
                + "{\"id\":41,\"type\":\"movie\",\"rank\":1,\"movie\":{\"title\":\"A\",\"ids\":{\"tmdb\":51001}}}]";
        List<NormalizedImportRecord> records = new ArrayList<>();

        items.parse(stream(json), CONTEXT, "lists/list-41.json", ImportRecordKind.LIST_ITEM, records::add);

        assertThat(records).extracting(NormalizedImportRecord::ordinal).containsExactly(0, 1);
        assertThat(records).extracting(record -> record.payload().path("rank").asInt()).containsExactly(2, 1);
    }

    @Test
    void watchedSummariesProduceSeriesOnlyAndNonMediaRecordsAreIgnored() {
        TraktItemParserImpl items = new TraktItemParserImpl(mapper);
        String json = "[{\"plays\":12,\"last_watched_at\":\"2025-06-03T03:30:00Z\",\"show\":{\"title\":\"Example\",\"year\":2020,\"aired_episodes\":84,\"ids\":{\"tmdb\":123}}},{\"type\":\"comment\",\"comment\":{\"id\":5}}]";
        List<NormalizedImportRecord> records = new ArrayList<>();
        items.parse(new java.io.ByteArrayInputStream(json.getBytes(java.nio.charset.StandardCharsets.UTF_8)), CONTEXT,
                "watched-shows.json", ImportRecordKind.WATCHED_SUMMARY, records::add);
        assertThat(records).hasSize(1);
        assertThat(((ImportTarget.ContentTarget) records.getFirst().target()).content()).isEqualTo(MediaReference.series("123"));
        assertThat(records.getFirst().payload().path("declaredPlays").asInt()).isEqualTo(12);
        assertThat(records.getFirst().payload().path("declaredLastWatchedOn").asString()).isEqualTo("2025-06-03");
        assertThat(records.getFirst().payload().path("airedEpisodeCount").asInt()).isEqualTo(84);
    }

    @Test
    void unresolvedEpisodeUsesParentQueryAndPreservesChildCoordinates() {
        String json = "[{\"id\":7,\"type\":\"episode\",\"episode\":{\"season\":0,\"number\":1,\"ids\":{\"tmdb\":9999}},\"show\":{\"title\":\"Unknown\",\"year\":2020,\"ids\":{\"trakt\":42}}}]";
        List<NormalizedImportRecord> records = new ArrayList<>();
        history.parse(new java.io.ByteArrayInputStream(json.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                CONTEXT, "history/part.json", records::add);
        assertThat(records).hasSize(1);
        assertThat(records.getFirst().target()).isNull();
        assertThat(records.getFirst().payload().path("sourceQuery").path("contentType").asString()).isEqualTo("SERIES");
        assertThat(records.getFirst().payload().path("sourceQuery").path("seasonNumber").asInt()).isZero();
        assertThat(records.getFirst().payload().path("sourceQuery").path("episodeNumber").asInt()).isEqualTo(1);
    }

    @Test
    void rejectsInvalidIdentityCoordinatesWithSourceLocator() {
        String json = "[{\"id\":\"bad-id\",\"type\":\"episode\",\"episode\":{\"number\":1},\"show\":{\"title\":\"Unknown\",\"ids\":{}}}]";
        assertThatThrownBy(() -> history.parse(new java.io.ByteArrayInputStream(json.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                CONTEXT, "history/part.json", ignored -> {})).isInstanceOf(com.watchwise.watchwise_api.common.exception.BadRequestException.class)
                .hasMessageContaining("history/part.json").hasMessageContaining("episode.season");
    }

    @Test
    void rejectsReleaseYearAboveSupportedMaximum() {
        TraktItemParserImpl items = new TraktItemParserImpl(mapper);
        String json = "[{\"type\":\"movie\",\"movie\":{\"title\":\"Example\",\"year\":10000,\"ids\":{}}}]";

        assertThatThrownBy(() -> items.parse(stream(json), CONTEXT, "lists/list-41.json",
                ImportRecordKind.LIST_ITEM, ignored -> {}))
                .isInstanceOf(com.watchwise.watchwise_api.common.exception.BadRequestException.class)
                .hasMessageContaining("movie.year");
    }

    @Test
    void declaredMediaTypeRequiresItsCorrespondingIdentityObject() {
        String json = "[{\"id\":9,\"type\":\"movie\",\"show\":{\"title\":\"Series masquerading as movie\",\"ids\":{\"tmdb\":123}}}]";
        assertThatThrownBy(() -> history.parse(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)),
                CONTEXT, "history/mismatched-type.json", ignored -> {}))
                .isInstanceOf(com.watchwise.watchwise_api.common.exception.BadRequestException.class)
                .hasMessageContaining("history/mismatched-type.json").hasMessageContaining("movie");
    }

    @Test
    void inventoryMatchesListPagesByExactIdAndNumericPartAndKeepsEmptyLists() throws Exception {
        TraktArchiveInspector inspector = new TraktArchiveInspector(mapper);
        Map<String, InputStream> files = new LinkedHashMap<>();
        for (String name : List.of("lists-lists.json", "lists-list-42-sample-1.json", "lists-list-42-sample-2.json")) {
            files.put(name, resource(name));
        }
        ImportInventory inventory = inspector.inspectFiles(files);
        assertThat(inventory.lists()).hasSize(6);
        assertThat(inventory.lists()).filteredOn(list -> list.sourceId().equals("42")).singleElement()
                .satisfies(list -> assertThat(list.itemCount()).isEqualTo(2));
        assertThat(inventory.lists()).filteredOn(list -> list.sourceId().equals("43")).singleElement()
                .satisfies(list -> assertThat(list.itemCount()).isZero());
        assertThat(inventory.lists()).filteredOn(list -> list.sourceId().equals("420")).singleElement()
                .satisfies(list -> assertThat(list.coverage()).isEqualTo("INCOMPLETE"));
        assertThat(TraktArchiveInspector.selectListFiles(List.of("lists-list-420-sample-1.json",
                "lists-list-42-sample-10.json", "lists-list-42-sample-2.json"), "42"))
                .containsExactly("lists-list-42-sample-2.json", "lists-list-42-sample-10.json");
        assertThatThrownBy(() -> TraktArchiveInspector.selectListFiles(List.of("lists-list-42-sample-0.json"), "42"))
                .isInstanceOf(com.watchwise.watchwise_api.common.exception.BadRequestException.class);
    }

    @Test
    void inventoryRecognizesSluggedPagesAndDoesNotTreatSlugYearAsPageNumber() {
        TraktArchiveInspector inspector = new TraktArchiveInspector(mapper);
        String metadata = "[{\"name\":\"One day\",\"item_count\":2,\"ids\":{\"trakt\":33626948}},"
                + "{\"name\":\"Remake list\",\"item_count\":1,\"ids\":{\"trakt\":33626949}},"
                + "{\"name\":\"Numeric slug pages\",\"item_count\":2,\"ids\":{\"trakt\":33626950}}]";
        String oneItem = "[{\"type\":\"movie\",\"movie\":{\"title\":\"Film\",\"ids\":{\"tmdb\":456}}}]";
        Map<String, InputStream> files = new LinkedHashMap<>();
        files.put("lists-lists.json", stream(metadata));
        files.put("lists-list-33626948-one-day-1.json", stream(oneItem));
        files.put("lists-list-33626948-one-day-2.json", stream(oneItem));
        files.put("lists-list-33626949-remake-oct-2023.json", stream(oneItem));
        files.put("lists-list-33626950-123-day-1000.json", stream(oneItem));
        files.put("lists-list-33626950-123-day-1001.json", stream(oneItem));

        ImportInventory inventory = inspector.inspectFiles(files);

        assertThat(inventory.lists()).filteredOn(list -> list.sourceId().equals("33626948")).singleElement()
                .satisfies(list -> assertThat(list.coverage()).isEqualTo("COMPLETE"));
        assertThat(inventory.lists()).filteredOn(list -> list.sourceId().equals("33626949")).singleElement()
                .satisfies(list -> assertThat(list.coverage()).isEqualTo("COMPLETE"));
        assertThat(inventory.lists()).filteredOn(list -> list.sourceId().equals("33626950")).singleElement()
                .satisfies(list -> assertThat(list.coverage()).isEqualTo("INCOMPLETE"));
        assertThat(TraktArchiveInspector.selectListFiles(List.of(
                "lists-list-33626948-one-day-2.json", "lists-list-33626948-one-day-1.json",
                "lists-list-33626949-remake-oct-2023.json"), "33626948"))
                .containsExactly("lists-list-33626948-one-day-1.json", "lists-list-33626948-one-day-2.json");
        assertThat(TraktArchiveInspector.selectListFiles(List.of(
                "lists-list-33626949-remake-oct-2023.json"), "33626949"))
                .containsExactly("lists-list-33626949-remake-oct-2023.json");
        assertThat(TraktArchiveInspector.selectListFiles(List.of(
                "lists-list-33626950-123-day-1001.json", "lists-list-33626950-123-day-1000.json"), "33626950"))
                .containsExactly("lists-list-33626950-123-day-1000.json", "lists-list-33626950-123-day-1001.json");
    }

    @Test
    void archiveEntriesUseNaturalNumericOrderingForRestartStableTraktPages() throws Exception {
        ImportLimits limits = new ImportLimits();
        byte[] archive = zip(new String[]{"export/watched-history-10.json", "export/watched-history-2.json",
                        "export/watched-history-1.json"},
                new String[]{"[]", "[]", "[]"});
        String key = UUID.randomUUID().toString();
        try (var fixture = R2StoreFixture.start(tempDir)) {
            var stored = fixture.store().store(new ByteArrayInputStream(archive), limits.maxUploadBytes(), key);
            SafeImportReader reader = new SafeImportReader(limits);
            var artifactReader = new ImportArtifactReader(fixture.store(), fixture.temporary(), limits);
            List<String> streamed = new ArrayList<>();
            try (ReadableImportArtifact artifact = artifactReader.open(
                    new ImportArtifactReference(key, stored.bytes(), stored.sha256()))) {
                assertThat(reader.entryNames(artifact)).containsExactly(
                        "watched-history-1.json", "watched-history-2.json", "watched-history-10.json");
                reader.forEachEntry(artifact, (path, input) -> streamed.add(path));
            }
            assertThat(streamed).containsExactly(
                    "watched-history-1.json", "watched-history-2.json", "watched-history-10.json");
            assertThat(fixture.server().requestCount("GET", fixture.objectPath(key))).isEqualTo(1);
        }
    }

    @Test
    void duplicatePageAliasesCannotMakeInMemoryInventoryAppearComplete() {
        String metadata = "[{\"name\":\"Forty two\",\"item_count\":2,\"ids\":{\"trakt\":42}}]";
        String oneItem = "[{\"type\":\"movie\",\"movie\":{\"title\":\"Film\",\"ids\":{\"tmdb\":456}}}]";
        Map<String, InputStream> files = new LinkedHashMap<>();
        files.put("lists-lists.json", stream(metadata));
        files.put("lists-list-42-1.json", stream(oneItem));
        files.put("lists-list-42-sample-1.json", stream(oneItem));
        TraktArchiveInspector inMemoryInspector = new TraktArchiveInspector(mapper);
        assertThat(inMemoryInspector.inspectFiles(files).lists()).singleElement()
                .extracting(ImportListInventory::coverage).isEqualTo("INCOMPLETE");
    }

    @Test
    void duplicatePageAliasesCannotMakeArchiveInventoryAppearComplete() throws Exception {
        String metadata = "[{\"name\":\"Forty two\",\"item_count\":2,\"ids\":{\"trakt\":42}}]";
        String oneItem = "[{\"type\":\"movie\",\"movie\":{\"title\":\"Film\",\"ids\":{\"tmdb\":456}}}]";
        byte[] archive = zip(new String[]{"export/lists-lists.json", "export/lists-list-42-1.json",
                        "export/lists-list-42-sample-1.json"},
                new String[]{metadata, oneItem, oneItem});
        ImportLimits limits = new ImportLimits();
        String key = UUID.randomUUID().toString();
        try (var fixture = R2StoreFixture.start(tempDir)) {
            var stored = fixture.store().store(new ByteArrayInputStream(archive), limits.maxUploadBytes(), key);
            var archiveContext = new ImportReadContext(CONTEXT.ownerId(), CONTEXT.jobId(), key,
                    CONTEXT.sourceNamespace(), CONTEXT.timeZone(), stored.bytes(), stored.sha256());
            SafeImportReader reader = new SafeImportReader(limits);
            TraktArchiveInspector archiveInspector = new TraktArchiveInspector(reader, mapper, limits);
            var artifactReader = new ImportArtifactReader(fixture.store(), fixture.temporary(), limits);
            try (ReadableImportArtifact artifact = artifactReader.open(archiveContext.artifact())) {
                assertThat(archiveInspector.inspect(archiveContext, artifact).lists()).singleElement()
                        .extracting(ImportListInventory::coverage).isEqualTo("INCOMPLETE");
            }
            assertThat(fixture.server().requestCount("GET", fixture.objectPath(key))).isEqualTo(1);
        }
    }

    @Test
    void metadataValidationErrorReportsTheActualNestedNumberedSourcePath() {
        String metadata = "[{\"name\":\"Missing count\",\"ids\":{\"trakt\":42}}]";
        TraktArchiveInspector inspector = new TraktArchiveInspector(mapper);
        assertThatThrownBy(() -> inspector.inspectFiles(Map.of("nested/lists-lists-7.json", stream(metadata))))
                .isInstanceOf(com.watchwise.watchwise_api.common.exception.BadRequestException.class)
                .hasMessageContaining("nested/lists-lists-7.json");
    }

    private void parse(TraktHistoryParser parser, String name, List<NormalizedImportRecord> sink) throws Exception {
        try (InputStream input = resource(name)) {
            parser.parse(input, CONTEXT, "history/" + name, sink::add);
        }
    }

    private InputStream resource(String name) {
        return getClass().getResourceAsStream(ROOT + name);
    }

    private InputStream stream(String value) { return new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8)); }

    private byte[] zip(String[] names, String[] contents) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream output = new ZipOutputStream(bytes)) {
            for (int i = 0; i < names.length; i++) {
                output.putNextEntry(new ZipEntry(names[i]));
                output.write(contents[i].getBytes(StandardCharsets.UTF_8));
                output.closeEntry();
            }
        }
        return bytes.toByteArray();
    }
}
