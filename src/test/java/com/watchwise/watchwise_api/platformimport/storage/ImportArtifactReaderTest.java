package com.watchwise.watchwise_api.platformimport.storage;

import com.watchwise.watchwise_api.common.exception.BadRequestException;
import com.watchwise.watchwise_api.common.exception.ServiceUnavailableException;
import com.watchwise.watchwise_api.platformimport.model.ImportArtifactReference;
import com.watchwise.watchwise_api.platformimport.model.StoredImportFile;
import com.watchwise.watchwise_api.platformimport.service.ImportLimits;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ImportArtifactReaderTest {
    @TempDir
    Path root;

    @Test
    void doesNotExposeAnArtifactWhoseDigestDiffersFromTheJob() throws Exception {
        String key = UUID.randomUUID().toString();
        byte[] bytes = new byte[]{1, 2, 3};
        try (var fixture = R2StoreFixture.start(root)) {
            fixture.store().store(new ByteArrayInputStream(bytes), 1024, key);
            var reader = reader(fixture);
            var reference = new ImportArtifactReference(key, bytes.length, "0".repeat(64));

            assertThatThrownBy(() -> reader.open(reference))
                    .isInstanceOfSatisfying(ImportArtifactIntegrityException.class,
                            failure -> assertThat(failure.reason())
                                    .isEqualTo(ImportArtifactIntegrityException.Reason.CORRUPT));
            assertThat(fixture.temporary().reservedBytes()).isZero();
        }
    }

    @Test
    void rejectsDeclaredSizesThatAreLargerOrSmallerThanTheStoredObject() throws Exception {
        String key = UUID.randomUUID().toString();
        byte[] bytes = new byte[]{1, 2, 3};
        try (var fixture = R2StoreFixture.start(root)) {
            StoredImportFile stored = fixture.store().store(new ByteArrayInputStream(bytes), 1024, key);
            var reader = reader(fixture);
            assertCorrupt(reader, new ImportArtifactReference(key, 4, stored.sha256()));
            assertCorrupt(reader, new ImportArtifactReference(key, 2, stored.sha256()));
            assertThat(fixture.temporary().reservedBytes()).isZero();
        }
    }

    @Test
    void reopensOneVerifiedMaterializationWithoutFetchingAgainAndClosesChildren() throws Exception {
        String key = UUID.randomUUID().toString();
        byte[] bytes = "verified-import".getBytes(StandardCharsets.UTF_8);
        try (var fixture = R2StoreFixture.start(root)) {
            StoredImportFile stored = fixture.store().store(new ByteArrayInputStream(bytes), 1024, key);
            ReadableImportArtifact artifact = reader(fixture).open(reference(stored));
            Path path = artifact.path();
            assertThat(path).exists();
            InputStream first = artifact.openStream();
            InputStream second = artifact.openStream();
            assertThatThrownBy(artifact::openStream).isInstanceOf(ServiceUnavailableException.class);
            assertThat(first.readAllBytes()).containsExactly(bytes);
            assertThat(second.readAllBytes()).containsExactly(bytes);
            first.close();
            second.close();
            try (InputStream reopened = artifact.openStream()) {
                assertThat(reopened.readAllBytes()).containsExactly(bytes);
            }
            assertThat(fixture.server().requestCount("GET", fixture.objectPath(key))).isEqualTo(1);

            artifact.close();
            artifact.close();
            assertThat(path).doesNotExist();
            assertThat(fixture.temporary().reservedBytes()).isZero();
            assertThatThrownBy(artifact::path).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(artifact::openStream).isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void materializesAnEmptyFileUsingOneByteOfReservation() throws Exception {
        String key = UUID.randomUUID().toString();
        try (var fixture = R2StoreFixture.start(root)) {
            StoredImportFile stored = fixture.store().store(new ByteArrayInputStream(new byte[0]), 1024, key);
            assertThat(stored.sha256())
                    .isEqualTo("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
            ReadableImportArtifact artifact = reader(fixture).open(reference(stored));
            assertThat(artifact.path()).hasSize(0);
            assertThat(fixture.temporary().reservedBytes()).isEqualTo(1);
            try (InputStream input = artifact.openStream()) {
                assertThat(input.readAllBytes()).isEmpty();
            }
            artifact.close();
            assertThat(fixture.temporary().reservedBytes()).isZero();
        }
    }

    @Test
    void handleCloseClosesAConsumerThatAbandonsItsStream() throws Exception {
        String key = UUID.randomUUID().toString();
        byte[] bytes = "consumer-abandoned-stream".getBytes(StandardCharsets.UTF_8);
        try (var fixture = R2StoreFixture.start(root)) {
            StoredImportFile stored = fixture.store().store(new ByteArrayInputStream(bytes), 1024, key);
            ReadableImportArtifact artifact = reader(fixture).open(reference(stored));
            InputStream child = artifact.openStream();
            child.read();
            Path path = artifact.path();

            artifact.close();

            assertThat(path).doesNotExist();
            assertThat(fixture.temporary().reservedBytes()).isZero();
            assertThatThrownBy(child::read).isInstanceOf(ImportStorageUnavailableException.class);
        }
    }

    @Test
    void releasesMaterializationWhenConsumerThrows() throws Exception {
        String key = UUID.randomUUID().toString();
        byte[] bytes = "consumer-error".getBytes(StandardCharsets.UTF_8);
        try (var fixture = R2StoreFixture.start(root)) {
            StoredImportFile stored = fixture.store().store(new ByteArrayInputStream(bytes), 1024, key);
            assertThatThrownBy(() -> {
                try (ReadableImportArtifact ignored = reader(fixture).open(reference(stored))) {
                    throw new IllegalStateException("consumer failure");
                }
            }).isInstanceOf(IllegalStateException.class).hasMessage("consumer failure");
            assertThat(fixture.temporary().reservedBytes()).isZero();
        }
    }

    @Test
    void rejectsPartialRemoteBodyAndReleasesTheReadReservation() throws Exception {
        String key = UUID.randomUUID().toString();
        byte[] bytes = "partial-body".getBytes(StandardCharsets.UTF_8);
        try (var fixture = R2StoreFixture.start(root)) {
            StoredImportFile stored = fixture.store().store(new ByteArrayInputStream(bytes), 1024, key);
            fixture.server().truncateNextGetBody(2);

            assertThatThrownBy(() -> reader(fixture).open(reference(stored)))
                    .isInstanceOf(ImportStorageUnavailableException.class);
            assertThat(fixture.temporary().reservedBytes()).isZero();
        }
    }

    @Test
    void rejectsInvalidReferenceMetadataAndBoundsConcurrentMaterializations() throws Exception {
        assertThatThrownBy(() -> new ImportArtifactReference("../bad", 1, "a".repeat(64)))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> new ImportArtifactReference(UUID.randomUUID().toString(), -1, "a".repeat(64)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ImportArtifactReference(UUID.randomUUID().toString(), 1, "not-a-digest"))
                .isInstanceOf(IllegalArgumentException.class);

        String firstKey = UUID.randomUUID().toString();
        String secondKey = UUID.randomUUID().toString();
        String thirdKey = UUID.randomUUID().toString();
        try (var fixture = R2StoreFixture.start(root)) {
            var first = fixture.store().store(new ByteArrayInputStream(new byte[]{1}), 1024, firstKey);
            var second = fixture.store().store(new ByteArrayInputStream(new byte[]{2}), 1024, secondKey);
            var third = fixture.store().store(new ByteArrayInputStream(new byte[]{3}), 1024, thirdKey);
            var reader = reader(fixture);
            try (ReadableImportArtifact firstRead = reader.open(reference(first));
                 ReadableImportArtifact secondRead = reader.open(reference(second))) {
                assertThatThrownBy(() -> reader.open(reference(third)))
                        .isInstanceOf(ServiceUnavailableException.class);
                assertThat(fixture.server().requestCount("GET", fixture.objectPath(thirdKey))).isZero();
            }
            assertThat(fixture.temporary().reservedBytes()).isZero();
        }
    }

    private void assertCorrupt(ImportArtifactReader reader, ImportArtifactReference reference) {
        assertThatThrownBy(() -> reader.open(reference))
                .isInstanceOfSatisfying(ImportArtifactIntegrityException.class,
                        failure -> assertThat(failure.reason())
                                .isEqualTo(ImportArtifactIntegrityException.Reason.CORRUPT));
    }

    private ImportArtifactReader reader(R2StoreFixture fixture) {
        return new ImportArtifactReader(fixture.store(), fixture.temporary(), new ImportLimits());
    }

    private ImportArtifactReference reference(StoredImportFile stored) {
        return new ImportArtifactReference(stored.key(), stored.bytes(), stored.sha256());
    }
}
