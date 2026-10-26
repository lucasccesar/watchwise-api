package com.watchwise.watchwise_api.platformimport.parser;

import com.watchwise.watchwise_api.common.exception.BadRequestException;
import com.watchwise.watchwise_api.platformimport.model.TemplateImportDocument;
import com.watchwise.watchwise_api.platformimport.service.ImportLimits;
import com.watchwise.watchwise_api.pickstemplate.entity.PickAllowedType;
import com.watchwise.watchwise_api.pickstemplate.entity.PickCategoryGroup;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PicksTemplateJsonParserTest {
    private final ImportLimits limits = new ImportLimits();
    private final PicksTemplateJsonParser parser = new PicksTemplateJsonParser(limits);

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
    void enforcesConfiguredByteAndDepthLimits() {
        limits.setMaxTemplateUploadBytes(valid().getBytes(StandardCharsets.UTF_8).length - 1);
        assertRejected(valid());
        limits.setMaxTemplateUploadBytes(5L * 1024 * 1024);
        limits.setMaxJsonDepth(1);
        assertRejected(valid());
    }

    @Test
    void acceptsUpperBoundsAndRejectsAggregateNomineeOverflow() {
        String open = "{\"name\":\"N\",\"type\":\"MOVIE\"}";
        String fixed500 = "{\"name\":\"N\",\"type\":\"MOVIE\",\"nominees\":["
                + ("\"N\",").repeat(499) + "\"N\"]}";
        assertThat(parse("{\"title\":\"T\",\"categories\":[" + (open + ",").repeat(99) + open + "]}")
                .categories()).hasSize(100);
        assertThat(parse("{\"title\":\"T\",\"categories\":[" + (fixed500 + ",").repeat(9) + fixed500 + "]}")
                .categories()).hasSize(10);
        assertRejected("{\"title\":\"T\",\"categories\":[" + (fixed500 + ",").repeat(10)
                + "{\"name\":\"N\",\"type\":\"MOVIE\",\"nominees\":[\"N\"]}]}");
    }

    @Test
    void rejectsUploadAboveFiveMebibytesAndNestedDuplicateKeys() {
        String prefix = "{\"title\":\"T\",\"description\":\"";
        String suffix = "\",\"categories\":[{\"name\":\"N\",\"type\":\"MOVIE\"}]}";
        assertRejected(prefix + "x".repeat(5 * 1024 * 1024 - prefix.length() - suffix.length() + 1) + suffix);
        assertRejected(baseCategory("\"type\":\"MOVIE\",\"nominees\":[{\"tmdbId\":\"1\",\"tmdbId\":\"2\"}]"));
    }

    private String valid() { return "{\"title\":\"T\",\"categories\":[{\"name\":\"N\",\"type\":\"MOVIE\"}]}"; }
    private String baseCategory(String fields) { return "{\"title\":\"T\",\"categories\":[{\"name\":\"N\"," + fields + "}]}"; }
    private TemplateImportDocument parse(String json) {
        return parser.parse(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)));
    }
    private void assertRejected(String json) {
        assertThatThrownBy(() -> parse(json)).as(json.substring(0, Math.min(100, json.length())))
                .isInstanceOf(BadRequestException.class);
    }
}
