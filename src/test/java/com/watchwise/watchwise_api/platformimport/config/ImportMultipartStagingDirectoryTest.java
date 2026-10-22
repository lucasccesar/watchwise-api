package com.watchwise.watchwise_api.platformimport.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class ImportMultipartStagingDirectoryTest {

    @TempDir
    Path tempDirectory;

    @Test
    @DisplayName("[ImportMultipartStagingDirectory] Should Refuse Broad Existing Permissions Without Changing Them - When Staging Path Is Shared")
    void shouldRefuseBroadExistingPermissionsWithoutChangingThemWhenStagingPathIsShared() throws IOException {
        assumeTrue(Files.getFileAttributeView(tempDirectory, PosixFileAttributeView.class) != null);
        Path staging = Files.createDirectory(tempDirectory.resolve("shared-staging"));
        Set<PosixFilePermission> sharedPermissions = PosixFilePermissions.fromString("rwxr-xr-x");
        Files.setPosixFilePermissions(staging, sharedPermissions);
        Path storage = Files.createDirectory(tempDirectory.resolve("durable-storage"));

        assertThatThrownBy(() -> new ImportMultipartStagingDirectory(staging.toString(), storage.toString()))
                .isInstanceOf(IllegalStateException.class);

        assertThat(Files.getPosixFilePermissions(staging)).isEqualTo(sharedPermissions);
    }

    @Test
    @DisplayName("[ImportMultipartStagingDirectory] Should Reject Overlapping Staging And Durable Roots - When Configured Paths Nest")
    void shouldRejectOverlappingStagingAndDurableRootsWhenConfiguredPathsNest() throws IOException {
        Path staging = Files.createDirectory(tempDirectory.resolve("shared-import-root"));
        Path storage = Files.createDirectory(staging.resolve("durable"));

        assertThatThrownBy(() -> new ImportMultipartStagingDirectory(staging.toString(), storage.toString()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
