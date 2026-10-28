package com.watchwise.watchwise_api.platformimport.config;

import com.watchwise.watchwise_api.platformimport.storage.ImportTemporaryFiles;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ImportMultipartStagingDirectoryTest {
    @TempDir
    Path tempDirectory;

    @Test
    void usesTheManagerOwnedMultipartDirectoryWithR2() throws Exception {
        try (var temporary = newTemporaryFiles("r2")) {
            var staging = new ImportMultipartStagingDirectory(temporary, "r2", "");
            assertThat(staging.directory()).isEqualTo(temporary.multipartDirectory().toRealPath());
            assertThat(staging.directory()).isDirectory();
            assertThat(Files.isSymbolicLink(staging.directory())).isFalse();
        }
    }

    @Test
    void allowsLocalDurableStorageOutsideTheTemporaryRoot() throws Exception {
        Path durable = Files.createDirectory(tempDirectory.resolve("durable-imports"));
        try (var temporary = newTemporaryFiles("local")) {
            var staging = new ImportMultipartStagingDirectory(temporary, "local", durable.toString());
            assertThat(staging.directory()).isEqualTo(temporary.multipartDirectory().toRealPath());
        }
    }

    @Test
    void rejectsLocalDurableStorageNestedUnderTheTemporaryRoot() throws Exception {
        try (var temporary = newTemporaryFiles("nested")) {
            Path durable = temporary.multipartDirectory().getParent().getParent().resolve("durable");
            assertThatThrownBy(() -> new ImportMultipartStagingDirectory(temporary, "local", durable.toString()))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void requiresLocalDurableStorageWhenLocalBackendIsSelected() throws Exception {
        try (var temporary = newTemporaryFiles("missing-durable")) {
            assertThatThrownBy(() -> new ImportMultipartStagingDirectory(temporary, "local", ""))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    private ImportTemporaryFiles newTemporaryFiles(String name) throws Exception {
        return new ImportTemporaryFiles(tempDirectory.resolve(name), 1024, 2048, 1, 1);
    }
}
