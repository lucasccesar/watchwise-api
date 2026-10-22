package com.watchwise.watchwise_api.platformimport.config;

import com.watchwise.watchwise_api.platformimport.service.ImportModeHandler;
import com.watchwise.watchwise_api.platformimport.service.ImportModeRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class ImportModeConfiguration {
    @Bean
    ImportModeRegistry importModeRegistry(ObjectProvider<ImportModeHandler> handlers) {
        return new ImportModeRegistry(handlers.orderedStream().toList());
    }
}
