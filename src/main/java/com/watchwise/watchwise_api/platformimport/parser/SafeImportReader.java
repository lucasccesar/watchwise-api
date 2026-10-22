package com.watchwise.watchwise_api.platformimport.parser;

import com.watchwise.watchwise_api.common.exception.BadRequestException;
import com.watchwise.watchwise_api.platformimport.service.ImportLimits;
import com.watchwise.watchwise_api.platformimport.storage.PrivateImportFileStore;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipFile;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.zip.CRC32;

@Component
public class SafeImportReader {
    private final PrivateImportFileStore store;
    private final ImportLimits limits;
    private final String storageKey;

    @Autowired
    public SafeImportReader(PrivateImportFileStore store, ImportLimits limits) {
        this(store, limits, null);
    }

    public SafeImportReader(PrivateImportFileStore store, ImportLimits limits, String storageKey) {
        this.store = store;
        this.limits = limits;
        this.storageKey = storageKey;
    }

    public String storageKey() { return storageKey; }

    public void forEachEntry(String key, BiConsumer<String, InputStream> consumer) {
        try {
            Path path = store.pathForReading(key);
            if (Files.size(path) > limits.maxUploadBytes()) throw new BadRequestException("Import archive exceeds compressed byte limit");
            List<String> javaNames = new ArrayList<>();
            try (java.util.zip.ZipFile javaZip = new java.util.zip.ZipFile(path.toFile())) {
                if (javaZip.size() > limits.maxEntries()) throw new BadRequestException("Import archive has too many entries");
                Enumeration<? extends java.util.zip.ZipEntry> entries = javaZip.entries();
                while (entries.hasMoreElements()) javaNames.add(normalize(entries.nextElement().getName()));
                try (ZipFile zip = ZipFile.builder().setPath(path).get()) {
                    List<ZipArchiveEntry> checked = validate(zip, javaNames);
                    String root = commonRoot(checked);
                    Set<String> strippedNames = new HashSet<>();
                    for (ZipArchiveEntry entry : checked) {
                        if (entry.isDirectory()) continue;
                        String name = normalize(entry.getName());
                        if (root != null) name = name.substring(root.length());
                        if (name.isEmpty() || !strippedNames.add(name)) throw new BadRequestException("Duplicate import archive path");
                    }
                    long[] total = {0};
                    for (ZipArchiveEntry entry : checked) {
                        if (entry.isDirectory()) continue;
                        String name = normalize(entry.getName());
                        if (root != null) name = name.substring(root.length());
                        try (CheckedEntryStream input = new CheckedEntryStream(zip.getInputStream(entry), entry, total)) {
                            consumer.accept(name, new NonClosingInputStream(input));
                            input.transferTo(java.io.OutputStream.nullOutputStream());
                            input.verify();
                        }
                    }
                }
            }
        } catch (BadRequestException ex) {
            throw ex;
        } catch (IOException | UncheckedIOException ex) {
            throw new BadRequestException("Malformed or unsupported import archive");
        }
    }

    private List<ZipArchiveEntry> validate(ZipFile zip, List<String> javaNames) {
        List<ZipArchiveEntry> checked = new ArrayList<>();
        Set<String> names = new HashSet<>();
        Enumeration<ZipArchiveEntry> entries = zip.getEntries();
        while (entries.hasMoreElements()) {
            ZipArchiveEntry entry = entries.nextElement();
            if (checked.size() >= limits.maxEntries()) throw new BadRequestException("Import archive has too many entries");
            String name = normalize(entry.getName());
            if (!names.add(name)) throw new BadRequestException("Duplicate import archive path");
            if (entry.isUnixSymlink() || entry.getGeneralPurposeBit().usesEncryption()
                    || !zip.canReadEntryData(entry)
                    || (entry.getMethod() != 0 && entry.getMethod() != 8)) {
                throw new BadRequestException("Unsupported import archive entry");
            }
            checked.add(entry);
        }
        if (checked.size() != javaNames.size()) throw new BadRequestException("Import archive entry count mismatch");
        for (int i = 0; i < checked.size(); i++) {
            if (!normalize(checked.get(i).getName()).equals(javaNames.get(i))) {
                throw new BadRequestException("Import archive entry name mismatch");
            }
        }
        return checked;
    }

    private String commonRoot(List<ZipArchiveEntry> entries) {
        String root = null;
        for (ZipArchiveEntry entry : entries) {
            if (entry.isDirectory()) continue;
            String name = normalize(entry.getName());
            int slash = name.indexOf('/');
            if (slash < 1) return null;
            String first = name.substring(0, slash + 1);
            if (root == null) root = first;
            else if (!root.equals(first)) return null;
        }
        return root;
    }

    private String normalize(String raw) {
        if (raw == null || raw.isEmpty() || raw.startsWith("/") || raw.startsWith("\\")
                || raw.matches("^[A-Za-z]:.*") || raw.indexOf('\\') >= 0) {
            throw new BadRequestException("Unsafe import archive path");
        }
        String name = Normalizer.normalize(raw, Normalizer.Form.NFC);
        String[] parts = name.split("/", -1);
        for (int i = 0; i < parts.length; i++) {
            String part = parts[i];
            if (part.isEmpty() && i == parts.length - 1 && name.endsWith("/")) continue;
            if (part.isEmpty() || part.equals(".") || part.equals("..")) throw new BadRequestException("Unsafe import archive path");
            for (int j = 0; j < part.length(); j++) {
                if (Character.isISOControl(part.charAt(j))) throw new BadRequestException("Unsafe import archive path");
            }
        }
        if (name.toLowerCase(java.util.Locale.ROOT).endsWith(".zip")) throw new BadRequestException("Nested ZIP archives are not supported");
        return name;
    }

    private class CheckedEntryStream extends FilterInputStream {
        private final ZipArchiveEntry entry;
        private final long[] total;
        private final CRC32 crc = new CRC32();
        private long bytes;

        CheckedEntryStream(InputStream source, ZipArchiveEntry entry, long[] total) {
            super(source);
            this.entry = entry;
            this.total = total;
        }

        @Override public int read() throws IOException {
            int value = super.read();
            if (value >= 0) {
                account(1);
                crc.update(value);
            }
            return value;
        }

        @Override public int read(byte[] buffer, int offset, int length) throws IOException {
            int count = in.read(buffer, offset, length);
            if (count > 0) {
                account(count);
                crc.update(buffer, offset, count);
            }
            return count;
        }

        private void account(int count) {
            if (count > limits.maxEntryBytes() - bytes || count > limits.maxExpandedBytes() - total[0]) {
                throw new BadRequestException("Import archive expanded byte limit exceeded");
            }
            bytes += count;
            total[0] += count;
        }

        void verify() {
            if (entry.getSize() != bytes || entry.getCrc() != crc.getValue()) {
                throw new BadRequestException("Import archive entry metadata mismatch");
            }
        }
    }

    private static class NonClosingInputStream extends FilterInputStream {
        NonClosingInputStream(InputStream input) {
            super(input);
        }

        @Override
        public void close() {
        }
    }
}
