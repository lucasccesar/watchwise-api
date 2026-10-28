package com.watchwise.watchwise_api.platformimport.config;

import com.watchwise.watchwise_api.platformimport.storage.ImportTemporaryFiles;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Locale;

public final class ImportMultipartStagingDirectory {
    private final Path directory;

    public ImportMultipartStagingDirectory(
            ImportTemporaryFiles temporaryFiles, String storageBackend, String configuredStoragePath) throws IOException {
        if (temporaryFiles == null || storageBackend == null) {
            throw new IllegalArgumentException("Import temporary storage configuration is required");
        }
        String backend = storageBackend.toLowerCase(Locale.ROOT);
        if (!backend.equals("local") && !backend.equals("r2")) {
            throw new IllegalArgumentException("Unsupported import storage backend");
        }

        Path temporaryRoot = temporaryFiles.rootDirectory().toRealPath();
        this.directory = temporaryFiles.multipartDirectory().toRealPath();
        if (!directory.startsWith(temporaryRoot)) {
            throw new IllegalArgumentException("Import multipart directory must be inside its private root");
        }

        if (backend.equals("local")) {
            if (configuredStoragePath == null || configuredStoragePath.isBlank()) {
                throw new IllegalArgumentException("Local import storage directory is required");
            }
            Path durableRoot = prospectivePath(Path.of(configuredStoragePath));
            if (overlaps(temporaryRoot, durableRoot)) {
                throw new IllegalArgumentException(
                        "Import temporary root must be separate from durable local storage");
            }
        }
    }

    public Path directory() {
        return directory;
    }

    private Path prospectivePath(Path path) throws IOException {
        if (!path.isAbsolute()) throw new IllegalArgumentException("Local import storage path must be absolute");
        Path normalized = path.normalize();
        if (normalized.getParent() == null) {
            throw new IllegalArgumentException("Local import storage path must be a dedicated directory");
        }
        rejectSymlinkAncestors(normalized);

        Path existing = normalized;
        while (existing != null && !Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) {
            existing = existing.getParent();
        }
        if (existing == null) {
            throw new IllegalArgumentException("Local import storage path has no existing ancestor");
        }
        return existing.toRealPath().resolve(existing.relativize(normalized)).normalize();
    }

    private void rejectSymlinkAncestors(Path path) {
        Path current = path.getRoot();
        for (Path part : path) {
            current = current.resolve(part);
            if (Files.isSymbolicLink(current)) {
                throw new IllegalArgumentException("Local import storage path cannot contain symbolic links");
            }
        }
    }

    private boolean overlaps(Path first, Path second) {
        return first.equals(second) || first.startsWith(second) || second.startsWith(first);
    }
}
