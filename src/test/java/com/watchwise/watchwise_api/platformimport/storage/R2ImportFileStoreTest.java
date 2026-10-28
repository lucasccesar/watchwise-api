package com.watchwise.watchwise_api.platformimport.storage;

import com.watchwise.watchwise_api.common.exception.BadRequestException;
import com.watchwise.watchwise_api.platformimport.model.StoredImportFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class R2ImportFileStoreTest {
    @TempDir
    java.nio.file.Path root;

    @Test
    void storesExactBytesWithApplicationDigestAndConditionalWrite() throws Exception {
        String key = UUID.randomUUID().toString();
        byte[] bytes = "original-import".getBytes(StandardCharsets.UTF_8);
        try (var fixture = R2StoreFixture.start(root)) {
            StoredImportFile stored = fixture.store().store(new ByteArrayInputStream(bytes), 1024, key);
            assertThat(stored.key()).isEqualTo(key);
            assertThat(stored.bytes()).isEqualTo(bytes.length);
            assertThat(stored.sha256()).isEqualTo(HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(bytes)));
            assertThat(fixture.server().storedBody(fixture.objectPath(key))).containsExactly(bytes);
            try (InputStream input = fixture.store().open(key)) {
                assertThat(input.readAllBytes()).containsExactly(bytes);
            }
            assertThat(fixture.server().lastHeaders("PUT", fixture.objectPath(key)))
                    .containsEntry("if-none-match", "*");
            assertThat(fixture.server().lastHeaders("PUT", fixture.objectPath(key)))
                    .containsEntry("x-amz-meta-watchwise-sha256", stored.sha256());
            assertThat(fixture.temporary().reservedBytes()).isZero();
        }
    }

    @Test
    void refusesBytesOverTheEffectiveLimitBeforeSendingPut() throws Exception {
        String key = UUID.randomUUID().toString();
        try (var fixture = R2StoreFixture.start(root)) {
            assertThatThrownBy(() -> fixture.store().store(
                    new ByteArrayInputStream(new byte[]{1, 2, 3}), 2, key))
                    .isInstanceOf(BadRequestException.class);
            assertThat(fixture.server().requestCount("PUT", fixture.objectPath(key))).isZero();
            assertThat(fixture.temporary().reservedBytes()).isZero();
        }
    }

    @Test
    void leavesAnObjectFromAnotherOperationUntouchedOnKeyCollision() throws Exception {
        String key = UUID.randomUUID().toString();
        byte[] original = "original".getBytes(StandardCharsets.UTF_8);
        byte[] competing = "other-upload".getBytes(StandardCharsets.UTF_8);
        try (var fixture = R2StoreFixture.start(root)) {
            fixture.store().store(new ByteArrayInputStream(original), 1024, key);
            assertThatThrownBy(() -> fixture.store().store(new ByteArrayInputStream(competing), 1024, key))
                    .isInstanceOf(ImportStorageKeyCollisionException.class);
            assertThat(fixture.server().storedBody(fixture.objectPath(key))).containsExactly(original);
            assertThat(fixture.server().requestCount("DELETE", fixture.objectPath(key))).isZero();
        }
    }

    @Test
    void recognizesItsOwnWriteAfterThePutResponseIsLost() throws Exception {
        String key = UUID.randomUUID().toString();
        byte[] bytes = "write-confirmation-lost".getBytes(StandardCharsets.UTF_8);
        try (var fixture = R2StoreFixture.start(root)) {
            fixture.server().disconnectAfterNextPut();
            StoredImportFile stored = fixture.store().store(new ByteArrayInputStream(bytes), 1024, key);
            assertThat(stored.sha256()).isEqualTo(HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(bytes)));
            assertThat(fixture.server().storedBody(fixture.objectPath(key))).containsExactly(bytes);
            assertThat(fixture.server().requestCount("PUT", fixture.objectPath(key))).isEqualTo(2);
            assertThat(fixture.server().requestCount("HEAD", fixture.objectPath(key))).isEqualTo(1);
        }
    }

    @Test
    void mapsMissingStoredObjectToIntegrityFailureAndProviderErrorsToUnavailable() throws Exception {
        String key = UUID.randomUUID().toString();
        try (var fixture = R2StoreFixture.start(root)) {
            assertThatThrownBy(() -> {
                try (InputStream input = fixture.store().open(key)) {
                    input.readAllBytes();
                }
            }).isInstanceOfSatisfying(ImportArtifactIntegrityException.class,
                    failure -> assertThat(failure.reason()).isEqualTo(ImportArtifactIntegrityException.Reason.MISSING));

            fixture.server().failNext("GET", fixture.objectPath(key), 503, 2);
            assertThatThrownBy(() -> fixture.store().open(key))
                    .isInstanceOf(ImportStorageUnavailableException.class)
                    .hasMessage("Import file storage is temporarily unavailable");

            fixture.server().failNext("GET", fixture.objectPath(key), 403);
            assertThatThrownBy(() -> fixture.store().open(key))
                    .isInstanceOf(ImportStorageUnavailableException.class);

            fixture.server().failNext("GET", fixture.objectPath(key), 429, 2);
            assertThatThrownBy(() -> fixture.store().open(key))
                    .isInstanceOf(ImportStorageUnavailableException.class);
        }
    }

    @Test
    void deleteIsIdempotentForObjectsThatAreAlreadyAbsent() throws Exception {
        String key = UUID.randomUUID().toString();
        try (var fixture = R2StoreFixture.start(root)) {
            fixture.store().delete(key);
            assertThat(fixture.server().containsObject(fixture.objectPath(key))).isFalse();
            fixture.store().store(new ByteArrayInputStream(new byte[]{1, 2, 3}), 1024, key);
            fixture.store().delete(key);
            fixture.store().delete(key);
            assertThat(fixture.server().containsObject(fixture.objectPath(key))).isFalse();
            assertThat(fixture.server().requestCount("DELETE", fixture.objectPath(key))).isEqualTo(3);
        }
    }

    @Test
    void abortsAResponseBodyThatExceedsTheOperationDeadline() throws Exception {
        String key = UUID.randomUUID().toString();
        try (var fixture = R2StoreFixture.start(root, Duration.ofMillis(250))) {
            fixture.store().store(new ByteArrayInputStream(new byte[]{1, 2, 3}), 1024, key);
            fixture.server().delayNextGetBody(Duration.ofSeconds(2));
            long started = System.nanoTime();
            assertThatThrownBy(() -> {
                try (InputStream input = fixture.store().open(key)) {
                    input.readAllBytes();
                }
            }).isInstanceOf(ImportStorageUnavailableException.class);
            assertThat(Duration.ofNanos(System.nanoTime() - started))
                    .isLessThan(Duration.ofSeconds(2));
        }
    }

    @Test
    void closesAnUnreadResponseWithoutWaitingForItsBody() throws Exception {
        String key = UUID.randomUUID().toString();
        try (var fixture = R2StoreFixture.start(root)) {
            fixture.store().store(new ByteArrayInputStream(new byte[]{1, 2, 3}), 1024, key);
            fixture.server().delayNextGetBody(Duration.ofSeconds(2));
            InputStream input = fixture.store().open(key);
            long started = System.nanoTime();
            input.close();
            assertThat(Duration.ofNanos(System.nanoTime() - started))
                    .isLessThan(Duration.ofSeconds(1));
        }
    }

    @Test
    void rejectsInvalidKeysBeforeMakingARequest() throws Exception {
        try (var fixture = R2StoreFixture.start(root)) {
            assertThatThrownBy(() -> fixture.store().open("../outside"))
                    .isInstanceOf(BadRequestException.class);
            assertThat(fixture.server().requests()).isEmpty();
        }
    }
}
