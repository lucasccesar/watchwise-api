package com.watchwise.watchwise_api.platformimport.config;

import com.watchwise.watchwise_api.platformimport.storage.ImportTemporaryFiles;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.util.unit.DataSize;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ImportMultipartConfigurationTest {
    @TempDir
    Path root;

    @Test
    void bindsTheActualMultipartStagingPathAndByteLimits() throws Exception {
        try (var temporary = new ImportTemporaryFiles(root.resolve("work"), 1024, 2048, 1, 1)) {
            var configuration = new ImportMultipartConfiguration();
            var staging = new ImportMultipartStagingDirectory(temporary, "r2", "");
            var multipart = configuration.multipartConfigElement(
                    staging, DataSize.ofBytes(1024), DataSize.ofBytes(2048));

            assertThat(multipart.getLocation()).isEqualTo(staging.directory().toString());
            assertThat(multipart.getMaxFileSize()).isEqualTo(1024);
            assertThat(multipart.getMaxRequestSize()).isEqualTo(2048);
            assertThat(multipart.getFileSizeThreshold()).isZero();
        }
    }
}
