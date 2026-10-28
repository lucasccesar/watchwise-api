package com.watchwise.watchwise_api.platformimport.config;

import com.watchwise.watchwise_api.platformimport.service.ImportLimits;
import com.watchwise.watchwise_api.platformimport.storage.ImportTemporaryFiles;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.util.unit.DataSize;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ImportTemporaryConfigurationTest {
    @TempDir
    Path root;

    @Test
    void createsTheMultipartAndWorkDirectoriesUnderTheConfiguredRoot() throws Exception {
        Path configuredRoot = root.resolve("work");
        var configuration = new ImportTemporaryConfiguration();

        try (ImportTemporaryFiles files = configuration.importTemporaryFiles(
                new ImportLimits(), configuredRoot.toString(), DataSize.ofMegabytes(51))) {
            assertThat(files.multipartDirectory()).isDirectory();
            assertThat(files.multipartDirectory()).startsWith(configuredRoot);
            assertThat(Files.isSymbolicLink(files.multipartDirectory())).isFalse();
        }

        assertThat(configuredRoot.resolveSibling("work")).isDirectory();
    }
}
