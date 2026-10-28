package com.watchwise.watchwise_api.platformimport.config;

import com.watchwise.watchwise_api.platformimport.service.ImportLimits;
import com.watchwise.watchwise_api.platformimport.storage.ImportTemporaryFiles;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.util.unit.DataSize;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ImportTemporaryConfigurationTest {
    @TempDir
    Path root;

    @Test
    void createsTheMultipartAndWorkDirectoriesUnderTheConfiguredRoot() throws Exception {
        Path configuredRoot = root.resolve("work");
        var configuration = new ImportTemporaryConfiguration();

        try (ImportTemporaryFiles files = configuration.importTemporaryFiles(
                new ImportLimits(), configuredRoot.toString(), "", DataSize.ofMegabytes(51))) {
            assertThat(files.rootDirectory()).isEqualTo(configuredRoot);
            assertThat(files.multipartDirectory()).isDirectory();
            assertThat(files.multipartDirectory()).startsWith(configuredRoot);
            assertThat(Files.isSymbolicLink(files.multipartDirectory())).isFalse();
        }

        assertThat(configuredRoot.resolveSibling("work")).isDirectory();
    }

    @Test
    void usesTheStagingBaseWhenTheTemporaryRootIsNotConfigured() throws Exception {
        Path stagingBase = root.resolve("staging-base");
        var configuration = new ImportTemporaryConfiguration();

        try (var files = configuration.importTemporaryFiles(
                new ImportLimits(), "", stagingBase.toString(), DataSize.ofMegabytes(51))) {
            assertThat(files.rootDirectory()).isEqualTo(stagingBase);
            assertThat(files.multipartDirectory()).startsWith(stagingBase);
        }
    }

    @Test
    void rejectsConflictingTemporaryAndStagingRoots() {
        var configuration = new ImportTemporaryConfiguration();
        assertThatThrownBy(() -> configuration.importTemporaryFiles(new ImportLimits(),
                root.resolve("temporary").toString(), root.resolve("staging").toString(),
                DataSize.ofMegabytes(51)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
