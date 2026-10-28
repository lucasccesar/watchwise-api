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
            @Value("${app.import.temporary-directory:}") String configuredTemporaryRoot,
            @Value("${app.import.staging-directory:}") String configuredStagingRoot,
            @Value("${spring.servlet.multipart.max-request-size:51MB}") DataSize maxRequestSize) throws IOException {
        Path temporaryRoot = normalizeConfiguredRoot(configuredTemporaryRoot);
        Path stagingRoot = normalizeConfiguredRoot(configuredStagingRoot);
        if (temporaryRoot != null && stagingRoot != null && !temporaryRoot.equals(stagingRoot)) {
            throw new IllegalArgumentException("Import temporary and staging directories must use the same private root");
        }
        Path root = stagingRoot != null ? stagingRoot : temporaryRoot;
        if (root == null) {
            root = Path.of(System.getProperty("java.io.tmpdir"), "watchwise-import-work").toAbsolutePath().normalize();
        }
        return new ImportTemporaryFiles(root, limits.maxUploadBytes(), maxRequestSize.toBytes(),
                limits.maxConcurrentUploadsPerProcess(), limits.maxConcurrentArtifactReadsPerProcess());
    }

    private static Path normalizeConfiguredRoot(String configuredRoot) {
        return configuredRoot == null || configuredRoot.isBlank()
                ? null : Path.of(configuredRoot).toAbsolutePath().normalize();
    }
}
