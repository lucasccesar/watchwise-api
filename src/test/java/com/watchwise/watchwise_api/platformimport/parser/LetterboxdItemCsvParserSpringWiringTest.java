package com.watchwise.watchwise_api.platformimport.parser;

import com.watchwise.watchwise_api.platformimport.model.ImportReadContext;
import com.watchwise.watchwise_api.platformimport.model.ImportRecordKind;
import com.watchwise.watchwise_api.platformimport.model.NormalizedImportRecord;
import com.watchwise.watchwise_api.platformimport.parser.impl.LetterboxdItemCsvParserImpl;
import com.watchwise.watchwise_api.platformimport.parser.impl.TraktItemParserImpl;
import com.watchwise.watchwise_api.platformimport.service.ImportLimits;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import tools.jackson.databind.ObjectMapper;

import java.io.ByteArrayInputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class LetterboxdItemCsvParserSpringWiringTest {
    private static final ImportReadContext CONTEXT = new ImportReadContext(
            UUID.fromString("00000000-0000-0000-0000-000000000001"),
            UUID.fromString("00000000-0000-0000-0000-000000000002"), "fixture", "letterboxd", ZoneId.of("UTC"));

    @Test
    @DisplayName("[spring] Should Create Letterboxd Parser Bean And Parse A CSV Row - When Real Dependencies Are Available")
    void shouldCreateLetterboxdParserBeanAndParseCsvRowWhenRealDependenciesAreAvailable() {
        try (var context = parserContext(LetterboxdItemCsvParserImpl.class)) {
            var parser = context.getBean(LetterboxdItemCsvParserImpl.class);
            List<NormalizedImportRecord> records = new ArrayList<>();

            parser.parse(new StringReader("Date,Name,Year,Letterboxd URI\n"
                            + "2025-06-01,Film,2020,https://letterboxd.com/film/film/\n"),
                    CONTEXT, "watchlist.csv", ImportRecordKind.WATCHLIST_ITEM, records::add);

            assertThat(records).singleElement().satisfies(record -> {
                assertThat(record.payload().path("title").asString()).isEqualTo("Film");
                assertThat(record.sourceKey().sourceId()).isEqualTo("https://letterboxd.com/film/film/");
            });
        }
    }

    @Test
    @DisplayName("[spring] Should Create Trakt Parser Bean And Parse A JSON Row - When Real Dependencies Are Available")
    void shouldCreateTraktParserBeanAndParseJsonRowWhenRealDependenciesAreAvailable() {
        try (var context = parserContext(TraktItemParserImpl.class)) {
            var parser = context.getBean(TraktItemParserImpl.class);
            List<NormalizedImportRecord> records = new ArrayList<>();

            parser.parse(new ByteArrayInputStream(("[{\"type\":\"movie\",\"movie\":{"
                            + "\"title\":\"Film\",\"year\":2020,\"ids\":{\"trakt\":1}}}]"
                    ).getBytes(StandardCharsets.UTF_8)), CONTEXT, "history.json",
                    ImportRecordKind.DIARY_EVENT, records::add);

            assertThat(records).singleElement().satisfies(record -> {
                assertThat(record.payload().path("title").asString()).isEqualTo("Film");
                assertThat(record.sourceKey().sourceId()).isEqualTo("1-1");
            });
        }
    }

    private AnnotationConfigApplicationContext parserContext(Class<?> parserType) {
        var context = new AnnotationConfigApplicationContext();
        context.registerBean(ImportLimits.class, ImportLimits::new);
        context.registerBean(ObjectMapper.class, (java.util.function.Supplier<ObjectMapper>) ObjectMapper::new);
        context.registerBean(CsvRecordReader.class);
        context.registerBean(parserType);
        context.refresh();
        return context;
    }
}
