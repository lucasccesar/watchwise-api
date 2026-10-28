package com.watchwise.watchwise_api.platformimport.config;

import com.watchwise.watchwise_api.platformimport.service.ImportLimits;
import com.watchwise.watchwise_api.platformimport.storage.ImportTemporaryFiles;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.unit.DataSize;

import java.io.IOException;
import java.nio.file.Path;

@Configuration(proxyBeanMethods = false)
public class ImportTemporaryConfiguration {
    @Bean(destroyMethod = "close")
    ImportTemporaryFiles importTemporaryFiles(
            ImportLimits limits,
            @Value("${app.import.temporary-directory:}") String configuredRoot,
            @Value("${spring.servlet.multipart.max-request-size:51MB}") DataSize maxRequestSize) throws IOException {
        Path root = configuredRoot == null || configuredRoot.isBlank()
                ? Path.of(System.getProperty("java.io.tmpdir"), "watchwise-import-work").toAbsolutePath().normalize()
                : Path.of(configuredRoot).toAbsolutePath().normalize();
        return new ImportTemporaryFiles(root, limits.maxUploadBytes(), maxRequestSize.toBytes(),
                limits.maxConcurrentUploadsPerProcess(), limits.maxConcurrentArtifactReadsPerProcess());
    }
}
