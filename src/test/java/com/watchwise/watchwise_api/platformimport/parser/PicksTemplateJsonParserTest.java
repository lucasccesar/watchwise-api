package com.watchwise.watchwise_api.platformimport.parser;

import com.watchwise.watchwise_api.common.exception.BadRequestException;
import com.watchwise.watchwise_api.platformimport.model.TemplateImportDocument;
import com.watchwise.watchwise_api.platformimport.service.ImportLimits;
import com.watchwise.watchwise_api.pickstemplate.entity.PickAllowedType;
import com.watchwise.watchwise_api.pickstemplate.entity.PickCategoryGroup;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.regex.Pattern;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PicksTemplateJsonParserTest {
    private final ImportLimits limits = new ImportLimits();
    private final PicksTemplateJsonParser parser = new PicksTemplateJsonParser(limits);

    @Test
    @DisplayName("[parse] Should Reject A Template Above The Hard Byte Limit - When Configuration Is Raised")
    void shouldRejectTemplateAboveHardByteLimitAsPayloadTooLarge() {
        limits.setMaxTemplateUploadBytes(10L * 1024 * 1024);
        byte[] bytes = new byte[5 * 1024 * 1024 + 1];

        assertThatThrownBy(() -> parser.parse(new ByteArrayInputStream(bytes)))
                .isInstanceOf(MaxUploadSizeExceededException.class);
    }

    @Test
    void parsesSpecFixtureWithOpenAndFixedCategories() throws Exception {
        try (var input = getClass().getResourceAsStream("/import/templates/classics-club.json")) {
            TemplateImportDocument document = parser.parse(input);
            assertThat(document.schemaVersion()).isEqualTo(1);
            assertThat(document.title()).isEqualTo("Classics Club Awards");
            assertThat(document.eligibilityStart()).isEqualTo(LocalDate.of(1960, 1, 1));
            assertThat(document.eligibilityEnd()).isEqualTo(LocalDate.of(2009, 12, 31));
            assertThat(document.categories()).hasSize(5);
            assertThat(document.categories().stream().map(c -> c.nomineesPresent()).toList())
                    .containsExactly(false, true, false, false, true);
            assertThat(document.categories().get(0).type()).isEqualTo(PickAllowedType.MOVIE);
            assertThat(document.categories().get(0).group()).isEqualTo(PickCategoryGroup.PRIMARY);
            assertThat(document.categories().get(4).displayOrder()).isEqualTo(5);
            assertThat(document.categories().get(1).nominees()).hasSize(3);
        }
    }

    @Test
    void preservesTextAndNormalizesOnlyTypeAndGroup() {
        var document = parse("{" +
                "\"title\":\"  Awards  \u00e9\",\"description\":\"Line  one\\nLine two\",\"instructions\":\"  Keep  this  \"," +
                "\"categories\":[{\"name\":\" Best  movie \",\"type\":\"  mOvIe  \",\"group\":\" sEcOnDaRy \"," +
                "\"nominees\":[\"  A  title  \"]}]}" );
        assertThat(document.title()).isEqualTo("  Awards  é");
        assertThat(document.description()).isEqualTo("Line  one\nLine two");
        assertThat(document.instructions()).isEqualTo("  Keep  this  ");
        assertThat(document.categories().getFirst().name()).isEqualTo(" Best  movie ");
        assertThat(document.categories().getFirst().group()).isEqualTo(PickCategoryGroup.SECONDARY);
        assertThat(document.categories().getFirst().nominees().getFirst().asString()).isEqualTo("  A  title  ");
    }

    @Test
    void acceptsTypedNomineesAndContextReference() {
        var document = parse("{\"title\":\"T\",\"categories\":[" +
                "{\"name\":\"M\",\"type\":\"MOVIE\",\"nominees\":[{\"tmdbId\":\"1\",\"title\":\"Film\",\"releaseYear\":2001},{\"title\":\"Other\",\"releaseYear\":2002}]}," +
                "{\"name\":\"E\",\"type\":\"EPISODE\",\"nominees\":[{\"seriesTmdbId\":\"2\",\"seasonNumber\":0,\"episodeNumber\":1}]}," +
                "{\"name\":\"P\",\"type\":\"PERSON\",\"nominees\":[{\"name\":\"Actor\",\"contextContent\":{\"type\":\"MOVIE\",\"tmdbId\":\"3\"}}]}]}" );
        assertThat(document.categories()).hasSize(3);
        assertThat(document.categories().get(2).nominees().getFirst().path("contextContent").path("tmdbId").asString()).isEqualTo("3");
    }

    @Test
    void rejectsMalformedAndUnknownInput() {
        for (String json : new String[]{
                "{\"title\":\"T\",\"categories\":[{\"name\":\"N\",\"type\":\"MOVIE\",\"nominees\":null}]}",
                "{\"title\":\"T\",\"categories\":[{\"name\":\"N\",\"type\":\"MOVIE\",\"nominees\":[]}]}",
                "{\"schemaVersion\":2,\"title\":\"T\",\"categories\":[]}",
                "{\"title\":\"T\",\"title\":\"U\",\"categories\":[]}",
                baseCategory("\"type\":\"SEASON\""),
                baseCategory("\"type\":\"MOVIE\",\"score\":5"),
                baseCategory("\"type\":\"MOVIE\",\"nominees\":[{\"title\":\"A\"}]"),
                baseCategory("\"type\":\"EPISODE\",\"nominees\":[{\"tmdbId\":\"1\"}]"),
                baseCategory("\"type\":\"PERSON\",\"nominees\":[{\"name\":\"A\",\"owner\":\"x\"}]"),
                baseCategory("\"type\":\"PERSON\",\"nominees\":[{\"name\":\"A\",\"contextContent\":{\"type\":\"MOVIE\",\"tmdbId\":\"1\",\"score\":5}}]"),
                baseCategory("\"type\":\"MOVIE\",\"nominees\":[\"   \"]"),
                "{\"title\":\"T\",\"categories\":[],\"creator\":1}",
                valid() + " {}"
        }) assertRejected(json);
    }

    @Test
    void enforcesDateUrlAndCardinalityBounds() {
        assertThat(schema().at("/properties/eligibility/$comment").asString())
                .contains("JSON Schema 2020-12 cannot compare sibling date strings", "Java parser enforces start <= end");
        for (String json : new String[]{
                "{\"title\":\"T\",\"eligibility\":{\"start\":\"2020-01-01\"},\"categories\":[]}",
                valid().replace("\"categories\"", "\"eligibility\":{\"start\":\"2021-01-01\",\"end\":\"2020-01-01\"},\"categories\""),
                valid().replace("\"categories\"", "\"coverImage\":\"file:///etc/passwd\",\"categories\""),
                valid().replace("\"T\"", "\"" + "x".repeat(121) + "\""),
                baseCategory("\"type\":\"MOVIE\",\"name\":\"" + "x".repeat(121) + "\""),
                valid().replace("\"categories\"", "\"coverImage\":\"https://example.org/" + "x".repeat(2049) + "\",\"categories\""),
                "{\"title\":\"T\",\"categories\":[" + ("{\"name\":\"N\",\"type\":\"MOVIE\"},").repeat(100) + "{\"name\":\"N\",\"type\":\"MOVIE\"}]}",
                baseCategory("\"type\":\"MOVIE\",\"nominees\":[" + ("\"N\",").repeat(500) + "\"N\"]")
        }) assertRejected(json);
        assertThat(parse(valid().replace("\"T\"", "\"" + "x".repeat(120) + "\"")).title()).hasSize(120);
    }

    @Test
    void enforcesPreparedTemplateTextAndOptionLimitsBeforeNormalization() {
        String maxDescription = "x".repeat(5000);
        String maxInstructions = "x".repeat(10000);
        assertThat(parse(valid().replace("\"categories\"", "\"description\":\"" + maxDescription + "\",\"categories\"")))
                .isNotNull();
        assertThat(parse(valid().replace("\"categories\"", "\"instructions\":\"" + maxInstructions + "\",\"categories\"")))
                .isNotNull();
        assertRejected(valid().replace("\"categories\"", "\"description\":\"" + "x".repeat(5001) + "\",\"categories\""));
        assertRejected(valid().replace("\"categories\"", "\"instructions\":\"" + "x".repeat(10001) + "\",\"categories\""));
        assertRejected(baseCategory("\"type\":\"MOVIE\",\"description\":\"" + "x".repeat(5001) + "\""));

        String open = "{\"name\":\"N\",\"type\":\"MOVIE\"}";
        assertThat(parse("{\"title\":\"T\",\"categories\":[" + (open + ",").repeat(49) + open + "]}")
                .categories()).hasSize(50);
        assertRejected("{\"title\":\"T\",\"categories\":[" + (open + ",").repeat(50) + open + "]}");

        String fixed100 = fixedCategory(100);
        assertThat(parse("{\"title\":\"T\",\"categories\":[" + fixed100 + "]}").categories())
                .hasSize(1);
        assertRejected("{\"title\":\"T\",\"categories\":[" + fixedCategory(101) + "]}");
        assertThat(parse("{\"title\":\"T\",\"categories\":[" + fixed100 + "," + fixed100 + "]}")
                .categories()).hasSize(2);
        assertRejected("{\"title\":\"T\",\"categories\":[" + fixed100 + "," + fixed100 + ","
                + fixedCategory(1) + "]}");

    }

    @Test
    @DisplayName("[parse] Should Reject The Configured Byte Limit - When The Document Exceeds It")
    void enforcesConfiguredByteAndDepthLimits() {
        limits.setMaxTemplateUploadBytes(valid().getBytes(StandardCharsets.UTF_8).length - 1);
        assertThatThrownBy(() -> parse(valid())).isInstanceOf(MaxUploadSizeExceededException.class);
        limits.setMaxTemplateUploadBytes(5L * 1024 * 1024);
        limits.setMaxJsonDepth(1);
        assertRejected(valid());
    }

    @Test
    void acceptsUpperBoundsAndRejectsAggregateNomineeOverflow() {
        String open = "{\"name\":\"N\",\"type\":\"MOVIE\"}";
        String fixed100 = fixedCategory(100);
        assertThat(parse("{\"title\":\"T\",\"categories\":[" + (open + ",").repeat(49) + open + "]}")
                .categories()).hasSize(50);
        assertThat(parse("{\"title\":\"T\",\"categories\":[" + fixed100 + "," + fixed100 + "]}")
                .categories()).hasSize(2);
        assertRejected("{\"title\":\"T\",\"categories\":[" + (open + ",").repeat(50) + open + "]}");
        assertRejected("{\"title\":\"T\",\"categories\":[" + fixedCategory(101) + "]}");
        assertRejected("{\"title\":\"T\",\"categories\":[" + fixed100 + "," + fixed100 + ","
                + fixedCategory(1) + "]}");

        JsonNode schema = schema();
        assertThat(schema.at("/properties/description/maxLength").intValue()).isEqualTo(5000);
        assertThat(schema.at("/properties/instructions/maxLength").intValue()).isEqualTo(10000);
        assertThat(schema.at("/properties/categories/maxItems").intValue()).isEqualTo(50);
        assertThat(schema.at("/$defs/mediaCategory/properties/description/maxLength").intValue()).isEqualTo(5000);
        assertThat(schema.at("/$defs/mediaCategory/properties/nominees/maxItems").intValue()).isEqualTo(100);
        assertThat(schema.at("/$comment").asString()).contains("total options <= 200");
    }

    @Test
    @DisplayName("[parse] Should Reject The Hard Byte Limit - When Upload Exceeds Five Mebibytes")
    void rejectsUploadAboveFiveMebibytesAndNestedDuplicateKeys() {
        String prefix = "{\"title\":\"T\",\"description\":\"";
        String suffix = "\",\"categories\":[{\"name\":\"N\",\"type\":\"MOVIE\"}]}";
        String oversized = prefix + "x".repeat(5 * 1024 * 1024 - prefix.length() - suffix.length() + 1) + suffix;
        assertThatThrownBy(() -> parse(oversized)).isInstanceOf(MaxUploadSizeExceededException.class);
        assertRejected(baseCategory("\"type\":\"MOVIE\",\"nominees\":[{\"tmdbId\":\"1\",\"tmdbId\":\"2\"}]"));
    }

    @Test
    void acceptsNumericallyEquivalentSchemaVersionAndMatchesIntegerBounds() {
        assertThat(parse(valid().replace("\"title\"", "\"schemaVersion\":1.0,\"title\"")).schemaVersion()).isEqualTo(1);
        assertThat(parse(valid().replace("\"title\"", "\"schemaVersion\":1e0,\"title\"")).schemaVersion()).isEqualTo(1);
        for (String version : new String[]{"0.999999999999999999", "1.000000000000000001", "2147483648", "1e100"})
            assertRejected(valid().replace("\"title\"", "\"schemaVersion\":" + version + ",\"title\""));

        assertThat(parse(mediaNominee("releaseYear", "9999")).categories()).hasSize(1);
        assertThat(parse(mediaNominee("releaseYear", "2000.0")).categories()).hasSize(1);
        assertRejected(mediaNominee("releaseYear", "0"));
        assertRejected(mediaNominee("releaseYear", "10000"));
        assertThat(parse(episodeNominee("seasonNumber", "2147483647")).categories()).hasSize(1);
        assertThat(parse(episodeNominee("seasonNumber", "1.0")).categories()).hasSize(1);
        assertRejected(episodeNominee("seasonNumber", "-1"));
        assertRejected(episodeNominee("seasonNumber", "1.1"));
        assertRejected(episodeNominee("seasonNumber", "2147483648"));
        assertThat(parse(episodeNominee("episodeNumber", "2147483647")).categories()).hasSize(1);
        assertRejected(episodeNominee("episodeNumber", "0"));
        assertRejected(episodeNominee("episodeNumber", "2147483648"));

        JsonNode schema = schema();
        assertThat(schema.at("/$defs/mediaNominee/properties/releaseYear/maximum").intValue()).isEqualTo(9999);
        assertThat(schema.at("/$defs/mediaNominee/properties/releaseYear/minimum").intValue()).isEqualTo(1);
        assertThat(schema.at("/$defs/episodeNominee/properties/seasonNumber/maximum").longValue()).isEqualTo(Integer.MAX_VALUE);
        assertThat(schema.at("/$defs/episodeNominee/properties/seasonNumber/minimum").intValue()).isZero();
        assertThat(schema.at("/$defs/episodeNominee/properties/episodeNumber/maximum").longValue()).isEqualTo(Integer.MAX_VALUE);
        assertThat(schema.at("/$defs/episodeNominee/properties/episodeNumber/minimum").intValue()).isEqualTo(1);
        assertThat(schema.at("/$defs/contextContent/oneOf/1/properties/seasonNumber/maximum").longValue()).isEqualTo(Integer.MAX_VALUE);
        assertThat(schema.at("/$defs/contextContent/oneOf/1/properties/episodeNumber/maximum").longValue()).isEqualTo(Integer.MAX_VALUE);
    }

    @Test
    void coverUrlParserAndSchemaPatternBothRequireHostAndRejectUserInfo() {
        Pattern pattern = Pattern.compile(schema().at("/properties/coverImage/pattern").asString(), Pattern.UNICODE_CHARACTER_CLASS);
        String validUrl = "https://example.com/covers/club.jpg";
        assertThat(parse(valid().replace("\"categories\"", "\"coverImage\":\"" + validUrl + "\",\"categories\"")))
                .isNotNull();
        assertThat(pattern.matcher(validUrl).matches()).isTrue();
        for (String url : new String[]{"https:///cover.jpg", "https://user@example.com/cover.jpg", "https://example.com/a\u2003b"}) {
            assertRejected(valid().replace("\"categories\"", "\"coverImage\":\"" + url + "\",\"categories\""));
            assertThat(pattern.matcher(url).matches()).isFalse();
        }
    }

    @Test
    void rejectsNonAsciiEnumCaseFoldingThatSchemaPatternDoesNotAllow() {
        String dotlessIType = "MOV\u0131E";
        Pattern movieTypePattern = Pattern.compile(
                schema().at("/$defs/movieCategory/properties/type/pattern").asString(), Pattern.UNICODE_CHARACTER_CLASS);
        assertThat(movieTypePattern.matcher(dotlessIType).matches()).isFalse();
        assertRejected(baseCategory("\"type\":\"" + dotlessIType + "\""));

        String dotlessIGroup = "PR\u0131MARY";
        Pattern groupPattern = Pattern.compile(schema().at("/$defs/group/pattern").asString(), Pattern.UNICODE_CHARACTER_CLASS);
        assertThat(groupPattern.matcher(dotlessIGroup).matches()).isFalse();
        assertRejected(baseCategory("\"type\":\"MOVIE\",\"group\":\"" + dotlessIGroup + "\""));
    }

    @Test
    void usesUnicodeCodePointsForLengthAndEcmaWhitespaceNormalization() {
        String emoji = "\uD83D\uDE03";
        String acceptedTitle = emoji.repeat(120);
        assertThat(parse(valid().replace("\"T\"", "\"" + acceptedTitle + "\"")).title()).isEqualTo(acceptedTitle);
        assertRejected(valid().replace("\"T\"", "\"" + emoji.repeat(121) + "\""));
        String acceptedCategoryName = emoji.repeat(120);
        assertThat(parse("{\"title\":\"T\",\"categories\":[{\"name\":\"" + acceptedCategoryName + "\",\"type\":\"MOVIE\"}]}" )
                .categories().getFirst().name()).isEqualTo(acceptedCategoryName);
        assertRejected("{\"title\":\"T\",\"categories\":[{\"name\":\"" + emoji.repeat(121) + "\",\"type\":\"MOVIE\"}]}");
        assertRejected(valid().replace("\"T\"", "\"\u00a0\ufeff\u2003\""));
        assertRejected(baseCategory("\"type\":\"MOVIE\",\"nominees\":[\"\u00a0\ufeff\u2003\"]"));
        assertRejected(baseCategory("\"type\":\"MOVIE\",\"nominees\":[{\"title\":\"\u00a0\ufeff\u2003\",\"releaseYear\":2000}]"));

        var normalized = parse(baseCategory("\"type\":\"\u00a0mOvIe\ufeff\",\"group\":\"\u2003sEcOnDaRy\u00a0\""))
                .categories().getFirst();
        assertThat(normalized.type()).isEqualTo(PickAllowedType.MOVIE);
        assertThat(normalized.group()).isEqualTo(PickCategoryGroup.SECONDARY);
    }

    @Test
    void mapsMalformedJsonAndIoFailuresButPropagatesUncheckedStreamFailures() {
        assertThatThrownBy(() -> parse("{\"title\":"))
                .isInstanceOf(BadRequestException.class).hasMessage("Invalid template JSON");
        assertThatThrownBy(() -> parse("{\"title\":\"T\",\"categories\":[]}"))
                .isInstanceOf(BadRequestException.class).hasMessage("categories must contain 1 to 50 entries");
        InputStream failingStream = new InputStream() {
            @Override public int read() throws IOException { throw new IOException("C:\\private\\secret.json"); }
        };
        assertThatThrownBy(() -> parser.parse(failingStream))
                .isInstanceOf(BadRequestException.class).hasMessage("Invalid template JSON");
        InputStream uncheckedFailingStream = new InputStream() {
            @Override public int read() { throw new IllegalStateException("internal stream bug"); }
        };
        assertThatThrownBy(() -> parser.parse(uncheckedFailingStream))
                .isInstanceOf(IllegalStateException.class).hasMessage("internal stream bug");
    }

    @Test
    void hidesBadRequestDetailsThrownWhileReadingInput() {
        InputStream failingStream = new InputStream() {
            @Override public int read() { throw new BadRequestException("internal detail"); }
        };
        assertThatThrownBy(() -> parser.parse(failingStream))
                .isInstanceOf(BadRequestException.class).hasMessage("Invalid template JSON");
    }

    private String valid() { return "{\"title\":\"T\",\"categories\":[{\"name\":\"N\",\"type\":\"MOVIE\"}]}"; }
    private String fixedCategory(int nomineeCount) {
        return "{\"name\":\"N\",\"type\":\"MOVIE\",\"nominees\":["
                + ("\"N\",").repeat(nomineeCount - 1) + "\"N\"]}";
    }
    private String baseCategory(String fields) { return "{\"title\":\"T\",\"categories\":[{\"name\":\"N\"," + fields + "}]}"; }
    private String mediaNominee(String field, String value) {
        return "{\"title\":\"T\",\"categories\":[{\"name\":\"N\",\"type\":\"MOVIE\",\"nominees\":[{\"title\":\"Film\",\"" + field + "\":" + value + "}]}]}";
    }
    private String episodeNominee(String field, String value) {
        String season = field.equals("seasonNumber") ? value : "0";
        String episode = field.equals("episodeNumber") ? value : "1";
        return "{\"title\":\"T\",\"categories\":[{\"name\":\"N\",\"type\":\"EPISODE\",\"nominees\":[{\"seriesTmdbId\":\"1\",\"seasonNumber\":" + season + ",\"episodeNumber\":" + episode + "}]}]}";
    }
    private JsonNode schema() {
        try (InputStream input = getClass().getResourceAsStream("/import/picks-template-v1.schema.json")) {
            return JsonMapper.builder().build().readTree(input);
        } catch (IOException ex) { throw new AssertionError(ex); }
    }
    private TemplateImportDocument parse(String json) {
        return parser.parse(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)));
    }
    private void assertRejected(String json) {
        assertThatThrownBy(() -> parse(json)).as(json.substring(0, Math.min(100, json.length())))
                .isInstanceOf(BadRequestException.class);
    }
}
