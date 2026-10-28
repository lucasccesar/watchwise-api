package com.watchwise.watchwise_api.platformimport.config;

import com.watchwise.watchwise_api.platformimport.storage.ImportTemporaryFiles;
import jakarta.servlet.MultipartConfigElement;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.unit.DataSize;

import java.io.IOException;

@Configuration(proxyBeanMethods = false)
public class ImportMultipartConfiguration {
    @Bean
    ImportMultipartStagingDirectory importMultipartStagingDirectory(
            ImportTemporaryFiles temporaryFiles,
            @Value("${app.import.storage.backend:local}") String storageBackend,
            @Value("${app.import.storage-directory:}") String storageDirectory) throws IOException {
        return new ImportMultipartStagingDirectory(temporaryFiles, storageBackend, storageDirectory);
    }

    @Bean
    MultipartConfigElement multipartConfigElement(
            ImportMultipartStagingDirectory stagingDirectory,
            @Value("${spring.servlet.multipart.max-file-size:50MB}") DataSize maxFileSize,
            @Value("${spring.servlet.multipart.max-request-size:51MB}") DataSize maxRequestSize) {
        if (maxFileSize.toBytes() <= 0 || maxRequestSize.compareTo(maxFileSize) < 0) {
            throw new IllegalArgumentException("Multipart byte limits must be positive and ordered");
        }
        return new MultipartConfigElement(stagingDirectory.directory().toString(),
                maxFileSize.toBytes(), maxRequestSize.toBytes(), 0);
    }
}
