package com.watchwise.watchwise_api.platformimport.config;

import com.watchwise.watchwise_api.platformimport.service.ImportModeHandler;
import com.watchwise.watchwise_api.platformimport.service.ImportModeRegistry;
import com.watchwise.watchwise_api.platformimport.parser.LetterboxdItemCsvParser;
import com.watchwise.watchwise_api.platformimport.parser.SafeImportReader;
import com.watchwise.watchwise_api.platformimport.parser.TraktItemParser;
import com.watchwise.watchwise_api.platformimport.parser.impl.LetterboxdArchiveInspector;
import com.watchwise.watchwise_api.platformimport.parser.impl.TraktArchiveInspector;
import com.watchwise.watchwise_api.platformimport.service.impl.LetterboxdArchiveImportHandler;
import com.watchwise.watchwise_api.platformimport.service.impl.TraktArchiveImportHandler;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

@Configuration(proxyBeanMethods = false)
public class ImportModeConfiguration {
    @Bean
    @ConditionalOnProperty(prefix = "app.import", name = "platform-archive-handlers-enabled",
            havingValue = "true", matchIfMissing = true)
    TraktArchiveImportHandler traktArchiveImportHandler(TraktArchiveInspector inspector,
                                                        SafeImportReader reader, TraktItemParser parser,
                                                        ObjectMapper objectMapper) {
        return new TraktArchiveImportHandler(inspector, reader, parser, objectMapper);
    }

    @Bean
    @ConditionalOnProperty(prefix = "app.import", name = "platform-archive-handlers-enabled",
            havingValue = "true", matchIfMissing = true)
    LetterboxdArchiveImportHandler letterboxdArchiveImportHandler(LetterboxdArchiveInspector inspector,
                                                                  SafeImportReader reader,
                                                                  LetterboxdItemCsvParser parser,
                                                                  ObjectMapper objectMapper) {
        return new LetterboxdArchiveImportHandler(inspector, reader, parser, objectMapper);
    }

    @Bean
    ImportModeRegistry importModeRegistry(ObjectProvider<ImportModeHandler> handlers) {
        return new ImportModeRegistry(handlers.orderedStream().toList());
    }
}
