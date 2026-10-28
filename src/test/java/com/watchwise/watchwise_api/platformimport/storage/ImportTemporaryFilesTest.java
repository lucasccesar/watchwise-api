package com.watchwise.watchwise_api.platformimport.storage;

import com.watchwise.watchwise_api.common.exception.TooManyRequestsException;
import com.watchwise.watchwise_api.common.exception.ServiceUnavailableException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class ImportTemporaryFilesTest {
    @TempDir
    Path root;

    @Test
    void removesTemporaryFileAndReleasesItsReservation() throws Exception {
        try (var files = new ImportTemporaryFiles(root.resolve("private"), 1024, 2048, 2, 2)) {
            Path path;
            try (var file = files.createUpload(1024)) {
                path = file.path();
                Files.write(path, new byte[]{1, 2, 3});
                assertThat(files.reservedBytes()).isEqualTo(1024);
                file.close();
                file.close();
                assertThat(files.reservedBytes()).isZero();
            }
            assertThat(path).doesNotExist();
            assertThat(files.reservedBytes()).isZero();
        }
    }

    @Test
    void rejectsAnUploadWhenAllUploadSlotsAreReserved() throws Exception {
        try (var files = new ImportTemporaryFiles(root.resolve("limited"), 1024, 2048, 1, 1);
             var first = files.createUpload(1024)) {
            assertThatThrownBy(() -> files.createUpload(1024))
                    .isInstanceOf(TooManyRequestsException.class);
        }
    }

    @Test
    void rejectsAnUnrepresentableTemporaryBudget() {
        assertThatThrownBy(() -> new ImportTemporaryFiles(root.resolve("overflow"), Long.MAX_VALUE,
                Long.MAX_VALUE, 2, 2))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void accountsForMultipartUploadAndReadReservationsTogether() throws Exception {
        try (var files = new ImportTemporaryFiles(root.resolve("combined"), 1024, 2048, 2, 2);
             var multipartOne = files.admitMultipart();
             var multipartTwo = files.admitMultipart();
             var uploadOne = files.createUpload(1024);
             var uploadTwo = files.createUpload(1024);
             var readOne = files.createRead(1024);
             var readTwo = files.createRead(1024)) {
            assertThat(files.reservedBytes()).isEqualTo(8192);
        }
    }

    @Test
    void refusesTheProcessWideTemporaryDirectory() {
        Path root = Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize();
        assertThatThrownBy(() -> new ImportTemporaryFiles(root, 1024, 2048, 1, 1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void refusesAnExistingDirectoryWithSharedPermissionsWithoutChangingIt() throws Exception {
        assumeTrue(Files.getFileAttributeView(root, PosixFileAttributeView.class) != null);
        Path shared = Files.createDirectory(root.resolve("shared"));
        var permissions = PosixFilePermissions.fromString("rwxr-xr-x");
        Files.setPosixFilePermissions(shared, permissions);

        assertThatThrownBy(() -> new ImportTemporaryFiles(shared, 1024, 2048, 1, 1))
                .isInstanceOf(IllegalStateException.class);
        assertThat(Files.getPosixFilePermissions(shared)).isEqualTo(permissions);
    }

    @Test
    void refusesASymbolicLinkInTheTemporaryRoot() throws Exception {
        Path target = Files.createDirectory(root.resolve("target"));
        Path link = root.resolve("linked-root");
        try {
            Files.createSymbolicLink(link, target);
        } catch (IOException | UnsupportedOperationException | SecurityException unavailable) {
            assumeTrue(false, "The active filesystem cannot create symbolic links");
        }

        assertThatThrownBy(() -> new ImportTemporaryFiles(link.resolve("work"), 1024, 2048, 1, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(link).isSymbolicLink();
        assertThat(target.resolve("work")).doesNotExist();
    }

    @Test
    void keepsAnotherActiveRunDirectoryLocked() throws Exception {
        Path sharedRoot = root.resolve("shared");
        try (var first = new ImportTemporaryFiles(sharedRoot, 1024, 2048, 1, 1);
             var second = new ImportTemporaryFiles(sharedRoot, 1024, 2048, 1, 1)) {
            Path firstRun = first.multipartDirectory().getParent();
            Path secondRun = second.multipartDirectory().getParent();
            assertThat(firstRun).isNotEqualTo(secondRun);
            assertThat(firstRun).exists();
            assertThat(secondRun).exists();
        }
    }

    @Test
    void removesRunDirectoryFromAProcessThatEnded() throws Exception {
        try (var files = new ImportTemporaryFiles(root.resolve("private"), 1024, 2048, 1, 1)) {
            Path storageRoot = files.multipartDirectory().getParent().getParent();
            Path abandoned = storageRoot.resolve("run-00000000-0000-0000-0000-000000000001");
            createPrivateDirectory(abandoned);
            createPrivateFile(abandoned.resolve("session.lock"));
            Files.writeString(abandoned.resolve("multipart-part"), "stale");

            assertThat(files.reapAbandoned()).isEqualTo(1);
            assertThat(abandoned).doesNotExist();
        }
    }

    @Test
    void recoversCleanupMarkerLeftByProcessTermination() throws Exception {
        Path storageRoot = root.resolve("private");
        createPrivateDirectory(storageRoot);
        String runId = "00000000-0000-0000-0000-000000000002";
        Path abandoned = storageRoot.resolve("run-" + runId);
        createPrivateDirectory(abandoned);
        createPrivateFile(abandoned.resolve("session.lock"));
        Files.writeString(abandoned.resolve("multipart-part"), "stale");
        Path marker = storageRoot.resolve("reap-" + runId + ".lock");
        createPrivateFile(marker);

        try (var files = new ImportTemporaryFiles(storageRoot, 1024, 2048, 1, 1)) {
            assertThat(abandoned).doesNotExist();
            assertThat(marker).doesNotExist();
            assertThat(files.reapAbandoned()).isZero();
        }
    }

    @Test
    void retainsDiskReservationUntilAnOpenFileCanBeRemoved() throws Exception {
        try (var files = new ImportTemporaryFiles(root.resolve("private"), 1024, 2048, 1, 1)) {
            var temporary = files.createUpload(1024);
            Path path = temporary.path();
            Files.delete(path);
            Files.createDirectory(path);
            Path unexpectedChild = Files.writeString(path.resolve("unexpected"), "still in use");
            temporary.close();
            assertThat(files.reservedBytes()).isEqualTo(1024);

            Files.delete(unexpectedChild);
            assertThat(files.reapAbandoned()).isEqualTo(1);
            assertThat(files.reservedBytes()).isZero();
            assertThat(path).doesNotExist();
        }
    }

    @Test
    void boundsUnreapedTemporaryFilesAndRetriesCleanupBeforeCreatingAnother() throws Exception {
        try (var files = new ImportTemporaryFiles(root.resolve("bounded-orphans"), 8, 16, 1, 1)) {
            List<Path> orphanDirectories = new ArrayList<>();
            for (int index = 0; index < 2; index++) {
                var temporary = files.createUpload(8);
                Path path = temporary.path();
                Files.delete(path);
                Files.createDirectory(path);
                orphanDirectories.add(path);
                Files.writeString(path.resolve("child"), "blocks deletion");
                temporary.close();
            }

            assertThat(files.reservedBytes()).isEqualTo(16);
            assertThatThrownBy(() -> files.createUpload(8))
                    .isInstanceOf(ServiceUnavailableException.class);

            for (Path orphanDirectory : orphanDirectories) {
                Files.delete(orphanDirectory.resolve("child"));
            }
            assertThat(files.reapAbandoned()).isEqualTo(2);
            assertThat(files.reservedBytes()).isZero();
            assertThat(orphanDirectories).allSatisfy(path -> assertThat(path).doesNotExist());
        }
    }

    private void createPrivateDirectory(Path path) throws Exception {
        if (Files.getFileAttributeView(path.getParent(), PosixFileAttributeView.class) != null) {
            Files.createDirectory(path, PosixFilePermissions.asFileAttribute(
                    PosixFilePermissions.fromString("rwx------")));
        } else {
            Files.createDirectory(path);
        }
    }

    private void createPrivateFile(Path path) throws Exception {
        if (Files.getFileAttributeView(path.getParent(), PosixFileAttributeView.class) != null) {
            Files.createFile(path, PosixFilePermissions.asFileAttribute(
                    PosixFilePermissions.fromString("rw-------")));
        } else {
            Files.createFile(path);
        }
    }
}
