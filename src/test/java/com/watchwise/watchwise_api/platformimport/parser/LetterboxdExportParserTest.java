package com.watchwise.watchwise_api.platformimport.parser;

import com.watchwise.watchwise_api.platformimport.model.ImportReadContext;
import com.watchwise.watchwise_api.platformimport.model.ImportRecordKind;
import com.watchwise.watchwise_api.platformimport.model.NormalizedImportRecord;
import com.watchwise.watchwise_api.platformimport.parser.impl.LetterboxdArchiveInspector;
import com.watchwise.watchwise_api.platformimport.parser.impl.LetterboxdHistoryCsvParser;
import com.watchwise.watchwise_api.platformimport.parser.impl.LetterboxdItemCsvParserImpl;
import com.watchwise.watchwise_api.platformimport.service.ImportLimits;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LetterboxdExportParserTest {
    private static final String ROOT = "/import/letterboxd/";
    private static final ImportReadContext CONTEXT = new ImportReadContext(
            UUID.fromString("00000000-0000-0000-0000-000000000001"),
            UUID.fromString("00000000-0000-0000-0000-000000000002"), "fixture", "letterboxd", ZoneId.of("UTC"));

    @Test
    @DisplayName("[parse] Should Preserve Multiline Quoted Review And Distinct Diary Event Uris - When Two Films Were Logged On The Same Day")
    void shouldPreserveMultilineQuotedReviewAndDistinctDiaryEventUrisWhenTwoFilmsWereLoggedOnSameDay() throws Exception {
        List<NormalizedImportRecord> records = new ArrayList<>();
        parse("LetterboxdHistoryCsvParser", "diary.csv", ImportRecordKind.DIARY_EVENT, records);
        parse("LetterboxdHistoryCsvParser", "reviews.csv", ImportRecordKind.REVIEW, records);

        assertThat(records).hasSize(3);
        if (records.size() == 3) {
            assertThat(records).extracting(record -> record.sourceKey().sourceId())
                    .containsExactly("https://letterboxd.com/user/film/one/", "https://letterboxd.com/user/film/two/",
                            "https://letterboxd.com/user/film/one/");
            assertThat(records.get(2).payload().path("review").asString()).isEqualTo("A quoted,\nmultiline review.");
            assertThat(records.get(0).payload().path("sourceRecordedOn").asString()).isEqualTo("2025-06-01");
            assertThat(records.get(0).payload().path("watchedOn").asString()).isEqualTo("2025-05-30");
        }
    }

    @Test
    @DisplayName("[parse] Should Keep Source Date Separate From Watched Date - When Diary Dates Differ")
    void shouldKeepSourceDateSeparateFromWatchedDateWhenDiaryDatesDiffer() throws Exception {
        List<NormalizedImportRecord> records = new ArrayList<>();
        parse("LetterboxdHistoryCsvParser", "diary.csv", ImportRecordKind.DIARY_EVENT, records);

        assertThat(records).hasSize(2);
        if (!records.isEmpty()) {
            assertThat(records.getFirst().payload().path("sourceRecordedOn").asString()).isEqualTo("2025-06-01");
            assertThat(records.getFirst().payload().path("watchedOn").asString()).isEqualTo("2025-05-30");
        }
    }

    @Test
    @DisplayName("[parse] Should Preserve Full Review Export Fields - When Reviews Csv Has Diary Metadata Columns")
    void shouldPreserveFullReviewExportFieldsWhenReviewsCsvHasDiaryMetadataColumns() throws Exception {
        String input = "Date,Name,Year,Letterboxd URI,Rating,Rewatch,Review,Tags,Watched Date\n"
                + "2025-06-01,Film,2020,https://letterboxd.com/user/film/one/,4.5,true,"
                + "A review,cinema,2025-05-30\n";
        List<NormalizedImportRecord> records = new ArrayList<>();

        parseReader("LetterboxdHistoryCsvParser", input, "reviews.csv", ImportRecordKind.REVIEW, records);

        assertThat(records).singleElement().satisfies(record -> {
            assertThat(record.payload().path("sourceRecordedOn").asString()).isEqualTo("2025-06-01");
            assertThat(record.payload().path("watchedOn").asString()).isEqualTo("2025-05-30");
            assertThat(record.payload().path("score").asInt()).isEqualTo(9);
            assertThat(record.payload().path("sourceRewatch").asBoolean()).isTrue();
            assertThat(record.payload().path("review").asString()).isEqualTo("A review");
            assertThat(record.payload().path("tags").size()).isEqualTo(1);
            assertThat(record.payload().path("tags").get(0).asString()).isEqualTo("cinema");
        });
    }

    @Test
    @DisplayName("[parseScore] Should Return Nine - When Rating Has Four And Half Stars")
    void shouldReturnNineWhenRatingHasFourAndHalfStars() throws Exception {
        assertThat(parseScore("4.5")).isEqualTo(9);
    }

    @Test
    @DisplayName("[parseScore] Should Return Null - When Rating Is Blank")
    void shouldReturnNullWhenRatingIsBlank() throws Exception {
        assertThat(parseScore(" ")).isNull();
    }

    @Test
    @DisplayName("[parseScore] Should Throw BadRequestException - When Rating Is Not An Exact Half Star")
    void shouldThrowBadRequestExceptionWhenRatingIsNotAnExactHalfStar() {
        assertThatThrownBy(() -> parseScore("4.25"))
                .isInstanceOf(com.watchwise.watchwise_api.common.exception.BadRequestException.class)
                .hasMessageContaining("Rating");
    }

    @Test
    @DisplayName("[parse] Should Preserve Watchlist Order And Use Content Uri Identity - When Watchlist Rows Are Parsed")
    void shouldPreserveWatchlistOrderAndUseContentUriIdentityWhenWatchlistRowsAreParsed() throws Exception {
        List<NormalizedImportRecord> records = new ArrayList<>();
        parse("LetterboxdItemCsvParserImpl", "watchlist.csv", ImportRecordKind.WATCHLIST_ITEM, records);

        assertThat(records).hasSize(2);
        if (records.size() == 2) assertThat(records).extracting(NormalizedImportRecord::ordinal).containsExactly(0, 1);
        assertThat(records).allSatisfy(record -> assertThat(record.sourceKey().sourceId())
                .startsWith("https://letterboxd.com/film/"));
    }

    @Test
    @DisplayName("[inspectFiles] Should Count Diary And Current Ratings - When Letterboxd Archive Files Are Present")
    void shouldCountDiaryAndCurrentRatingsWhenLetterboxdArchiveFilesArePresent() throws Exception {
        var inventory = new LetterboxdArchiveInspector(new CsvRecordReader(new ImportLimits())).inspectFiles(Map.of(
                "diary.csv", resource("diary.csv"), "reviews.csv", resource("reviews.csv"),
                "watched.csv", resource("watched.csv"), "ratings.csv", resource("ratings.csv"),
                "watchlist.csv", resource("watchlist.csv"), "list-v7.csv", resource("list-v7.csv")));

        assertThat(inventory.categories()).anySatisfy(category -> assertThat(category)
                .isEqualTo(new com.watchwise.watchwise_api.platformimport.model.ImportCategoryInventory(
                        ImportRecordKind.DIARY_EVENT, 2L, true)));
        assertThat(inventory.lists()).singleElement().satisfies(list -> {
            assertThat(list.name()).isEqualTo("Synthetic List");
            assertThat(list.itemCount()).isEqualTo(2);
        });
    }

    @Test
    @DisplayName("[inspectFiles] Should Reject A Recognized CSV With An Unexpected Header")
    void shouldRejectRecognizedCsvWithUnexpectedHeader() {
        String malformed = "Date,Title,Year\n2025-06-01,Film,2020\n";
        var inspector = new LetterboxdArchiveInspector(new CsvRecordReader(new ImportLimits()));

        assertThatThrownBy(() -> inspector.inspectFiles(Map.of("reviews.csv",
                new ByteArrayInputStream(malformed.getBytes(StandardCharsets.UTF_8)))))
                .isInstanceOf(com.watchwise.watchwise_api.common.exception.BadRequestException.class)
                .hasMessageContaining("reviews.csv")
                .hasMessageContaining("Unexpected CSV header");
    }

    @Test
    @DisplayName("[parseList] Should Parse Native Metadata And Items - When Version Seven Sections Are Present")
    void shouldParseMetadataAndItemsAsCsvRecordsWhenVersionSevenSectionsArePresent() throws Exception {
        List<NormalizedImportRecord> records = new ArrayList<>();
        var parser = new LetterboxdItemCsvParserImpl(new CsvRecordReader(new ImportLimits()));
        var metadata = parser.parseList(new java.io.InputStreamReader(resource("list-v7.csv"), StandardCharsets.UTF_8),
                CONTEXT, "lists/list-v7.csv", records::add);

        assertThat(metadata).hasSize(1);
        if (!metadata.isEmpty()) assertThat(metadata.getFirst()).satisfies(list -> {
                assertThat(list.name()).isEqualTo("Synthetic List");
                assertThat(list.description()).isEqualTo("A list, with commas");
            });
        assertThat(records).extracting(NormalizedImportRecord::kind)
                .containsExactly(ImportRecordKind.LIST_METADATA, ImportRecordKind.LIST_ITEM, ImportRecordKind.LIST_ITEM);
        if (records.size() == 3) {
            assertThat(records.get(0).payload().path("sourceDate").asString()).isEqualTo("2026-10-20");
            assertThat(records.get(0).payload().path("sourceUri").asString())
                    .isEqualTo("https://letterboxd.com/user/list/synthetic/");
            assertThat(records.get(0).payload().path("tags").toString()).isEqualTo("[\"sample\",\"archive\"]");
            assertThat(records).extracting(NormalizedImportRecord::ordinal).containsExactly(0, 0, 1);
            assertThat(records.get(1).payload().path("title").asString()).isEqualTo("Listed Film");
            assertThat(records.get(1).payload().path("sourceContentUri").asString())
                    .isEqualTo("https://letterboxd.com/film/listed/");
            assertThat(records.get(1).payload().path("sourceQuery").path("title").asString()).isEqualTo("Listed Film");
            assertThat(records.get(1).payload().path("rank").asInt()).isEqualTo(1);
            assertThat(records.get(1).payload().path("position").asInt()).isEqualTo(1);
            assertThat(records.get(1).payload().path("description").asString()).isEqualTo("Item note, with comma");
            assertThat(records.get(2).payload().path("rank").asInt()).isEqualTo(2);
            assertThat(records.get(2).payload().path("description").asString()).isEqualTo("Second note");
        }
    }

    @Test
    @DisplayName("[parseList] Should Accept Native Metadata Header Without Version - When Exact Header Identifies Format")
    void shouldAcceptNativeMetadataHeaderWithoutVersionWhenExactHeaderIdentifiesFormat() {
        String input = "Date,Name,Tags,URL,Description\n"
                + "2026-10-20,Versionless List,curated,https://letterboxd.com/user/list/versionless/,Notes\n"
                + "Position,Name,Year,URL,Description\n"
                + "1,Film,2020,https://letterboxd.com/film/film/,Item note\n";
        List<NormalizedImportRecord> records = new ArrayList<>();
        var parser = new LetterboxdItemCsvParserImpl(new CsvRecordReader(new ImportLimits()));

        parser.parseList(new java.io.StringReader(input), CONTEXT, "list-v7.csv", records::add);

        assertThat(records).extracting(NormalizedImportRecord::kind)
                .containsExactly(ImportRecordKind.LIST_METADATA, ImportRecordKind.LIST_ITEM);
        assertThat(records.getFirst().payload().path("title").asString()).isEqualTo("Versionless List");
        assertThat(records.getLast().payload().path("rank").asInt()).isEqualTo(1);
    }

    @Test
    @DisplayName("[parseList] Should Require Version For Legacy Metadata Header - When Version Row Is Missing")
    void shouldRequireVersionForLegacyMetadataHeaderWhenVersionRowIsMissing() {
        String input = "Name,Description,Tags,URL\nFavorites,Notes,,https://letterboxd.com/user/list/favorites/\n"
                + "Date,Name,Year,Letterboxd URI,Rating,Watched Date\n";
        var parser = new LetterboxdItemCsvParserImpl(new CsvRecordReader(new ImportLimits()));

        assertThatThrownBy(() -> parser.parseList(new java.io.StringReader(input), CONTEXT,
                "list-v7.csv", ignored -> {}))
                .isInstanceOf(com.watchwise.watchwise_api.common.exception.BadRequestException.class)
                .hasMessageContaining("version 7");
    }

    @Test
    @DisplayName("[parse] Should Reject Invalid Decimal Rating With Source Locator - When Rating Cannot Be Parsed")
    void shouldRejectInvalidDecimalRatingWithSourceLocatorWhenRatingCannotBeParsed() throws Exception {
        String input = "Date,Name,Year,Letterboxd URI,Rating\n2025-06-01,Film,2020,https://letterboxd.com/film/film/,not-a-number\n";
        assertThatThrownBy(() -> parseReader("LetterboxdHistoryCsvParser", input, "ratings/bad.csv",
                ImportRecordKind.CURRENT_RATING, new ArrayList<>()))
                .isInstanceOf(com.watchwise.watchwise_api.common.exception.BadRequestException.class)
                .hasMessageContaining("ratings/bad.csv").hasMessageContaining("Rating");
    }

    private Integer parseScore(String raw) throws Exception {
        return LetterboxdHistoryCsvParser.parseScore(raw);
    }

    private void parse(String className, String file, ImportRecordKind kind, List<NormalizedImportRecord> output) throws Exception {
        try (InputStream input = resource(file)) {
            parseReader(className, new String(input.readAllBytes(), StandardCharsets.UTF_8), file, kind, output);
        }
    }

    private void parseReader(String className, String value, String sourcePath, ImportRecordKind kind,
                             List<NormalizedImportRecord> output) throws Exception {
        var parser = new LetterboxdHistoryCsvParser(new CsvRecordReader(new ImportLimits()));
        parser.parse(new java.io.StringReader(value), CONTEXT, sourcePath, kind, output::add);
    }

    private InputStream resource(String name) {
        InputStream input = getClass().getResourceAsStream(ROOT + name);
        if (input == null) throw new IllegalStateException("Missing synthetic fixture " + name);
        return input;
    }
}
