package com.watchwise.watchwise_api.platformimport.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;

@Component
@DependsOn("privateImportFileStore")
public final class ImportMultipartStagingDirectory {
    private static final Set<PosixFilePermission> PRIVATE_PERMISSIONS =
            PosixFilePermissions.fromString("rwx------");

    private final Path directory;

    public ImportMultipartStagingDirectory(
            @Value("${app.import.staging-directory}") String configuredPath,
            @Value("${app.import.storage-directory}") String configuredStoragePath) throws IOException {
        Path path = Path.of(configuredPath);
        Path storagePath = Path.of(configuredStoragePath);
        if (!path.isAbsolute() || !storagePath.isAbsolute()) {
            throw new IllegalArgumentException("Import storage directories must be absolute");
        }
        Path normalizedPath = path.normalize();
        Path normalizedStoragePath = storagePath.normalize();
        Path temporaryRoot = Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize();
        if (normalizedPath.getParent() == null || normalizedPath.equals(temporaryRoot)
                || temporaryRoot.startsWith(normalizedPath) || overlaps(normalizedPath, normalizedStoragePath)) {
            throw new IllegalArgumentException("Import multipart staging directory must be dedicated and separate from durable storage");
        }

        Path durableRoot = storagePath.toRealPath();
        Path existingAncestor = normalizedPath;
        while (existingAncestor != null && !Files.exists(existingAncestor, LinkOption.NOFOLLOW_LINKS)) {
            existingAncestor = existingAncestor.getParent();
        }
        if (existingAncestor == null) throw new IllegalArgumentException("Import multipart staging path has no existing ancestor");
        Path prospectivePath = existingAncestor.toRealPath()
                .resolve(existingAncestor.relativize(normalizedPath)).normalize();
        if (overlaps(prospectivePath, durableRoot)) {
            throw new IllegalArgumentException("Import multipart staging directory must be separate from durable storage");
        }

        createPrivateDirectory(normalizedPath);
        this.directory = normalizedPath.toRealPath();
        if (overlaps(directory, durableRoot)) {
            throw new IllegalArgumentException("Import multipart staging directory must be separate from durable storage");
        }
    }

    public Path directory() {
        return directory;
    }

    private void createPrivateDirectory(Path path) throws IOException {
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            if (Files.isSymbolicLink(path) || !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalArgumentException("Import multipart staging path must be a real directory");
            }
            requirePrivatePermissions(path);
            return;
        }

        Path parent = path.getParent();
        Files.createDirectories(parent);
        if (Files.getFileAttributeView(parent, PosixFileAttributeView.class) != null) {
            FileAttribute<?> permissions = PosixFilePermissions.asFileAttribute(PRIVATE_PERMISSIONS);
            Files.createDirectory(path, permissions);
        } else {
            Files.createDirectory(path);
        }
    }

    private void requirePrivatePermissions(Path path) throws IOException {
        if (Files.getFileAttributeView(path, PosixFileAttributeView.class) == null) return;
        Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS);
        if (!permissions.equals(PRIVATE_PERMISSIONS)) {
            throw new IllegalStateException("Existing import multipart staging directory must already have private permissions");
        }
    }

    private boolean overlaps(Path first, Path second) {
        return first.equals(second) || first.startsWith(second) || second.startsWith(first);
    }
}
