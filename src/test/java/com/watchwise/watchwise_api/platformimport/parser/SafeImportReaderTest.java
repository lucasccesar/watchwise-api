package com.watchwise.watchwise_api.platformimport.parser;

import com.watchwise.watchwise_api.common.exception.BadRequestException;
import com.watchwise.watchwise_api.platformimport.model.StoredImportFile;
import com.watchwise.watchwise_api.platformimport.service.ImportLimits;
import com.watchwise.watchwise_api.platformimport.storage.PrivateImportFileStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.*;

class SafeImportReaderTest {
    @TempDir Path root;

    private byte[] zip(String[] names, byte[][] contents) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream out = new ZipOutputStream(bytes)) {
            for (int i = 0; i < names.length; i++) {
                out.putNextEntry(new ZipEntry(names[i]));
                out.write(contents[i]);
                out.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    private SafeImportReader reader(byte[] bytes, ImportLimits limits, List<String> names) throws Exception {
        PrivateImportFileStore store = new PrivateImportFileStore(root, limits);
        StoredImportFile stored = store.store(new ByteArrayInputStream(bytes), limits.maxUploadBytes());
        return new SafeImportReader(store, limits, stored.key());
    }

    @Test
    @DisplayName("[forEachEntry] Should Preserve Relative Paths - When Archive Has A Common Root")
    void shouldPreserveRelativePathsWhenArchiveHasCommonRoot() throws Exception {
        ImportLimits limits = new ImportLimits();
        List<String> names = new ArrayList<>();
        SafeImportReader reader = reader(zip(new String[]{"export/diary.csv", "export/deleted/diary.csv", "export/orphaned/reviews.csv"},
                new byte[][]{{1}, {2}, {3}}), limits, names);
        reader.forEachEntry(reader.storageKey(), (name, input) -> names.add(name));
        assertThat(names).containsExactly("diary.csv", "deleted/diary.csv", "orphaned/reviews.csv");
        assertThat(names).doesNotContain("reviews.csv");
    }

    @Test
    @DisplayName("[forEachEntry] Should Reject Unsafe Names Before Callback - When Any Entry Is Invalid")
    void shouldRejectUnsafeNamesBeforeCallbackWhenAnyEntryIsInvalid() throws Exception {
        for (String name : List.of("../a.csv", "/a.csv", "C:/a.csv", "a\\b.csv", "./a.csv", "x.zip")) {
            List<String> seen = new ArrayList<>();
            SafeImportReader reader = reader(zip(new String[]{"safe.csv", name}, new byte[][]{{1}, {2}}), new ImportLimits(), seen);
            assertThatThrownBy(() -> reader.forEachEntry(reader.storageKey(), (n, input) -> seen.add(n)))
                    .as(name).isInstanceOf(BadRequestException.class);
            assertThat(seen).isEmpty();
        }
    }

    @Test
    @DisplayName("[forEachEntry] Should Limit Actual Entry And Total Decompressed Bytes - When Payload Exceeds Quota")
    void shouldLimitActualEntryAndTotalDecompressedBytesWhenPayloadExceedsQuota() throws Exception {
        byte[] archive = zip(new String[]{"a.csv", "b.csv"}, new byte[][]{new byte[6], new byte[6]});
        ImportLimits entryLimit = new ImportLimits(1024, 100, 5, 20, 10, 1024, 32, 3, 1024, 4096, 2);
        SafeImportReader entryReader = reader(archive, entryLimit, new ArrayList<>());
        assertThatThrownBy(() -> entryReader.forEachEntry(entryReader.storageKey(), (n, input) -> {
            try { input.transferTo(java.io.OutputStream.nullOutputStream()); } catch (java.io.IOException e) { throw new RuntimeException(e); }
        })).isInstanceOf(BadRequestException.class);
        ImportLimits totalLimit = new ImportLimits(1024, 10, 8, 20, 10, 1024, 32, 3, 1024, 4096, 2);
        SafeImportReader totalReader = reader(archive, totalLimit, new ArrayList<>());
        assertThatThrownBy(() -> totalReader.forEachEntry(totalReader.storageKey(), (n, input) -> {
            try { input.transferTo(java.io.OutputStream.nullOutputStream()); } catch (java.io.IOException e) { throw new RuntimeException(e); }
        })).isInstanceOf(BadRequestException.class);
    }

    @Test
    @DisplayName("[forEachEntry] Should Reject Unicode-Normalized Duplicate Paths - Before Calling Consumer")
    void shouldRejectUnicodeNormalizedDuplicatePathsBeforeCallingConsumer() throws Exception {
        List<String> seen = new ArrayList<>();
        byte[] archive = zip(new String[]{"caf\u00e9.csv", "cafe\u0301.csv"}, new byte[][]{{1}, {2}});
        SafeImportReader reader = reader(archive, new ImportLimits(), seen);

        assertThatThrownBy(() -> reader.forEachEntry(reader.storageKey(), (name, input) -> seen.add(name)))
                .isInstanceOf(BadRequestException.class);
        assertThat(seen).isEmpty();
    }

    @Test
    @DisplayName("[forEachEntry] Should Reject Excess Central Directory Entries - Before Calling Consumer")
    void shouldRejectExcessCentralDirectoryEntriesBeforeCallingConsumer() throws Exception {
        int count = 2001;
        String[] names = new String[count];
        byte[][] contents = new byte[count][];
        for (int i = 0; i < count; i++) {
            names[i] = "items/item-" + i + ".csv";
            contents[i] = new byte[0];
        }
        byte[] archive = zip(names, contents);
        assertThat(archive.length).isLessThan(50 * 1024 * 1024);
        List<String> seen = new ArrayList<>();
        SafeImportReader reader = reader(archive, new ImportLimits(), seen);

        assertThatThrownBy(() -> reader.forEachEntry(reader.storageKey(), (name, input) -> seen.add(name)))
                .isInstanceOf(BadRequestException.class);
        assertThat(seen).isEmpty();
    }

    @Test
    @DisplayName("[forEachEntry] Should Reject Adversarial Entry Count - Before Materializing Entry Objects")
    void shouldRejectAdversarialEntryCountBeforeMaterializingEntryObjects() throws Exception {
        ImportLimits limits = new ImportLimits();
        Path archivePath = root.resolve("many-entries.zip");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(archivePath))) {
            for (int i = 0; i < 10_000; i++) {
                out.putNextEntry(new ZipEntry("entry-" + i + ".csv"));
                out.closeEntry();
            }
        }
        assertThat(Files.size(archivePath)).isLessThan(limits.maxUploadBytes());

        PrivateImportFileStore store = new PrivateImportFileStore(root, limits);
        StoredImportFile stored;
        try (var input = Files.newInputStream(archivePath)) {
            stored = store.store(input, limits.maxUploadBytes());
        }
        SafeImportReader reader = new SafeImportReader(store, limits);
        List<String> seen = new ArrayList<>();

        assertThatThrownBy(() -> reader.forEachEntry(stored.key(), (name, input) -> seen.add(name)))
                .isInstanceOf(BadRequestException.class);
        assertThat(seen).isEmpty();
    }

    @Test
    @DisplayName("[forEachEntry] Should Validate Actual Entry CRC - When Central Directory Metadata Is Corrupted")
    void shouldValidateActualEntryCrcWhenCentralDirectoryMetadataIsCorrupted() throws Exception {
        byte[] archive = zip(new String[]{"a.csv"}, new byte[][]{{1, 2, 3}});
        byte[] signature = {0x50, 0x4b, 0x01, 0x02};
        int centralDirectoryOffset = -1;
        for (int i = 0; i <= archive.length - signature.length; i++) {
            if (archive[i] == signature[0] && archive[i + 1] == signature[1]
                    && archive[i + 2] == signature[2] && archive[i + 3] == signature[3]) {
                centralDirectoryOffset = i;
                break;
            }
        }
        assertThat(centralDirectoryOffset).isGreaterThanOrEqualTo(0);
        Arrays.fill(archive, centralDirectoryOffset + 16, centralDirectoryOffset + 20, (byte) 0);

        SafeImportReader reader = reader(archive, new ImportLimits(), new ArrayList<>());
        assertThatThrownBy(() -> reader.forEachEntry(reader.storageKey(), (name, input) -> {}))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    @DisplayName("[forEachEntry] Should Retain Reader Ownership - When Consumer Closes Its Entry Stream")
    void shouldRetainReaderOwnershipWhenConsumerClosesItsEntryStream() throws Exception {
        SafeImportReader reader = reader(zip(new String[]{"a.csv"}, new byte[][]{{1, 2, 3}}),
                new ImportLimits(), new ArrayList<>());

        assertThatCode(() -> reader.forEachEntry(reader.storageKey(), (name, input) -> {
            try {
                input.read();
                input.close();
            } catch (java.io.IOException ex) {
                throw new java.io.UncheckedIOException(ex);
            }
        })).doesNotThrowAnyException();
    }
}
