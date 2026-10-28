package com.watchwise.watchwise_api.platformimport.config;

import com.watchwise.watchwise_api.platformimport.service.ImportModeHandler;
import com.watchwise.watchwise_api.platformimport.service.ImportModeRegistry;
import com.watchwise.watchwise_api.platformimport.model.ImportSourceFormat;
import com.watchwise.watchwise_api.platformimport.parser.LetterboxdItemCsvParser;
import com.watchwise.watchwise_api.platformimport.parser.SafeImportReader;
import com.watchwise.watchwise_api.platformimport.parser.TraktItemParser;
import com.watchwise.watchwise_api.platformimport.parser.impl.LetterboxdArchiveInspector;
import com.watchwise.watchwise_api.platformimport.parser.impl.TraktArchiveInspector;
import com.watchwise.watchwise_api.platformimport.service.impl.LetterboxdArchiveImportHandler;
import com.watchwise.watchwise_api.platformimport.service.impl.TraktArchiveImportHandler;
import com.watchwise.watchwise_api.platformimport.service.impl.SingleListImportHandler;
import com.watchwise.watchwise_api.platformimport.service.impl.SingleWatchlistImportHandler;
import com.watchwise.watchwise_api.platformimport.storage.ImportArtifactReader;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

@Configuration(proxyBeanMethods = false)
public class ImportModeConfiguration {
    @Bean
    SingleListImportHandler traktSingleListImportHandler(ImportArtifactReader artifacts, TraktItemParser traktParser,
                                                         LetterboxdItemCsvParser letterboxdParser,
                                                         ObjectMapper objectMapper) {
        return new SingleListImportHandler(ImportSourceFormat.TRAKT_JSON, artifacts, traktParser,
                letterboxdParser, objectMapper);
    }

    @Bean
    SingleListImportHandler letterboxdSingleListImportHandler(ImportArtifactReader artifacts, TraktItemParser traktParser,
                                                              LetterboxdItemCsvParser letterboxdParser,
                                                              ObjectMapper objectMapper) {
        return new SingleListImportHandler(ImportSourceFormat.LETTERBOXD_CSV, artifacts, traktParser,
                letterboxdParser, objectMapper);
    }

    @Bean
    SingleWatchlistImportHandler traktSingleWatchlistImportHandler(ImportArtifactReader artifacts,
                                                                   TraktItemParser traktParser,
                                                                   LetterboxdItemCsvParser letterboxdParser) {
        return new SingleWatchlistImportHandler(ImportSourceFormat.TRAKT_JSON, artifacts, traktParser, letterboxdParser);
    }

    @Bean
    SingleWatchlistImportHandler letterboxdSingleWatchlistImportHandler(ImportArtifactReader artifacts,
                                                                        TraktItemParser traktParser,
                                                                        LetterboxdItemCsvParser letterboxdParser) {
        return new SingleWatchlistImportHandler(ImportSourceFormat.LETTERBOXD_CSV, artifacts,
                traktParser, letterboxdParser);
    }

    @Bean
    @ConditionalOnProperty(prefix = "app.import", name = "platform-archive-handlers-enabled",
            havingValue = "true", matchIfMissing = true)
    TraktArchiveImportHandler traktArchiveImportHandler(TraktArchiveInspector inspector,
                                                        SafeImportReader reader, TraktItemParser parser,
                                                        ObjectMapper objectMapper, ImportArtifactReader artifacts) {
        return new TraktArchiveImportHandler(inspector, reader, parser, objectMapper, artifacts);
    }

    @Bean
    @ConditionalOnProperty(prefix = "app.import", name = "platform-archive-handlers-enabled",
            havingValue = "true", matchIfMissing = true)
    LetterboxdArchiveImportHandler letterboxdArchiveImportHandler(LetterboxdArchiveInspector inspector,
                                                                  SafeImportReader reader,
                                                                  LetterboxdItemCsvParser parser,
                                                                  ObjectMapper objectMapper,
                                                                  ImportArtifactReader artifacts) {
        return new LetterboxdArchiveImportHandler(inspector, reader, parser, objectMapper, artifacts);
    }

    @Bean
    ImportModeRegistry importModeRegistry(ObjectProvider<ImportModeHandler> handlers) {
        return new ImportModeRegistry(handlers.orderedStream().toList());
    }
}
