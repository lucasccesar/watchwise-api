package com.watchwise.watchwise_api.platformimport.storage;

import com.watchwise.watchwise_api.common.exception.BadRequestException;
import com.watchwise.watchwise_api.platformimport.model.StoredImportFile;
import com.watchwise.watchwise_api.platformimport.parser.SafeImportReader;
import com.watchwise.watchwise_api.platformimport.service.ImportLimits;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Assumptions;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.*;

class PrivateImportFileStoreTest {
    @TempDir Path root;

    @Test
    @DisplayName("[componentScan] Should Start Both Import Reader Components - When Storage Directory Is Absolute And Limits Are Bound")
    void shouldStartBothImportReaderComponentsWhenStorageDirectoryIsAbsoluteAndLimitsAreBound() {
        Path storageDirectory = root.resolve("component-scan-store").toAbsolutePath();
        new ApplicationContextRunner()
                .withUserConfiguration(ImportReaderTestConfiguration.class)
                .withPropertyValues(
                        "app.import.storage-directory=" + storageDirectory,
                        "app.import.max-upload-bytes=12345",
                        "app.import.max-concurrent-uploads-per-process=1")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(PrivateImportFileStore.class);
                    assertThat(context).hasSingleBean(SafeImportReader.class);
                    assertThat(context.getBean(ImportLimits.class).maxUploadBytes()).isEqualTo(12345L);
                    assertThat(Files.isDirectory(storageDirectory)).isTrue();
                });
    }

    @Test
    @DisplayName("[store] Should Persist Generated Key Across Instances - When Store Is Reopened")
    void shouldPersistGeneratedKeyAcrossInstancesWhenStoreIsReopened() throws Exception {
        PrivateImportFileStore first = new PrivateImportFileStore(root, new ImportLimits());
        StoredImportFile stored = first.store(new ByteArrayInputStream("abc".getBytes()), 10);
        assertThat(stored.bytes()).isEqualTo(3);
        assertThat(stored.sha256()).isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
        assertThat(stored.key()).matches("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}");
        if (Files.getFileAttributeView(root, PosixFileAttributeView.class) != null) {
            assertThat(Files.getPosixFilePermissions(root.resolve(stored.key())))
                    .containsExactlyInAnyOrder(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
            assertThat(Files.getPosixFilePermissions(root))
                    .containsExactlyInAnyOrder(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
                            PosixFilePermission.OWNER_EXECUTE);
        }
        try (InputStream input = new PrivateImportFileStore(root, new ImportLimits()).open(stored.key())) {
            assertThat(input.readAllBytes()).isEqualTo("abc".getBytes());
        }
        first.delete(stored.key());
        first.delete(stored.key());
        assertThat(Files.list(root)).isEmpty();
    }

    @Test
    void shouldClassifyMissingValidKeyAsArtifactIntegrityFailure() throws Exception {
        PrivateImportFileStore store = new PrivateImportFileStore(root, new ImportLimits());
        String missingKey = java.util.UUID.randomUUID().toString();
        assertThatThrownBy(() -> store.open(missingKey))
                .isInstanceOfSatisfying(ImportArtifactIntegrityException.class,
                        failure -> assertThat(failure.reason())
                                .isEqualTo(ImportArtifactIntegrityException.Reason.MISSING));
    }

    @Test
    @DisplayName("[store] Should Leave No File - When Limit Or Stream Failure Occurs")
    void shouldLeaveNoFileWhenLimitOrStreamFailureOccurs() throws Exception {
        PrivateImportFileStore store = new PrivateImportFileStore(root, new ImportLimits());
        assertThatThrownBy(() -> store.store(new ByteArrayInputStream(new byte[11]), 10))
                .isInstanceOf(BadRequestException.class);
        InputStream broken = new InputStream() {
            int calls;
            @Override public int read() throws IOException { if (++calls > 2) throw new IOException("broken"); return 'a'; }
        };
        assertThatThrownBy(() -> store.store(broken, 10)).isInstanceOf(IOException.class);
        assertThat(Files.list(root)).isEmpty();
    }

    @Test
    @DisplayName("[open] Should Reject Traversal And Symlink Escape - When Key Is Not A Private Regular File")
    void shouldRejectTraversalAndSymlinkEscapeWhenKeyIsNotPrivateRegularFile() throws Exception {
        PrivateImportFileStore store = new PrivateImportFileStore(root, new ImportLimits());
        assertThatThrownBy(() -> store.open("../outside")).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> store.delete("C:\\outside")).isInstanceOf(BadRequestException.class);
        Path outside = Files.createTempFile("outside-import", ".tmp");
        try {
            Path link = root.resolve("8e0a2ace-11e9-4eb5-b626-6159585a2730");
            try {
                Files.createSymbolicLink(link, outside);
            } catch (UnsupportedOperationException | IOException ex) {
                Assumptions.assumeTrue(false, "Symbolic links are unavailable in this environment");
            }
            assertThatThrownBy(() -> store.open(link.getFileName().toString())).isInstanceOf(BadRequestException.class);
        } finally { Files.deleteIfExists(outside); }
    }

    @Test
    @DisplayName("[store] Should Enforce Configured Concurrent Upload Limit - When Another Upload Is Active")
    void shouldEnforceConfiguredConcurrentUploadLimitWhenAnotherUploadIsActive() throws Exception {
        ImportLimits limits = new ImportLimits(1024, 4096, 2048, 20, 10, 1024, 32, 3, 1024, 4096, 1);
        PrivateImportFileStore store = new PrivateImportFileStore(root, limits);
        CountDownLatch firstReadStarted = new CountDownLatch(1);
        CountDownLatch releaseFirstRead = new CountDownLatch(1);
        var executor = Executors.newSingleThreadExecutor();
        try {
            var firstUpload = executor.submit(() -> store.store(new InputStream() {
                private boolean read;

                @Override
                public int read() throws IOException {
                    if (read) return -1;
                    read = true;
                    firstReadStarted.countDown();
                    try {
                        if (!releaseFirstRead.await(5, TimeUnit.SECONDS)) throw new IOException("Timed out waiting to release test upload");
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                        throw new IOException("Interrupted test upload", ex);
                    }
                    return -1;
                }
            }, 10));

            assertThat(firstReadStarted.await(5, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> store.store(new ByteArrayInputStream(new byte[]{1}), 10))
                    .isInstanceOf(com.watchwise.watchwise_api.common.exception.TooManyRequestsException.class);
            releaseFirstRead.countDown();
            firstUpload.get(5, TimeUnit.SECONDS);
        } finally {
            releaseFirstRead.countDown();
            executor.shutdownNow();
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ComponentScan(basePackageClasses = {PrivateImportFileStore.class, SafeImportReader.class})
    @EnableConfigurationProperties(ImportLimits.class)
    static class ImportReaderTestConfiguration {
        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }
    }
}
