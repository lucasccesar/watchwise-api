package com.watchwise.watchwise_api.platformimport.storage;

import com.watchwise.watchwise_api.common.exception.BadRequestException;
import com.watchwise.watchwise_api.common.exception.TooManyRequestsException;
import com.watchwise.watchwise_api.platformimport.model.StoredImportFile;
import com.watchwise.watchwise_api.platformimport.service.ImportLimits;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.Semaphore;

@Component
public class PrivateImportFileStore implements ImportFileStore {
    private static final FileAttribute<?> PRIVATE_FILE_PERMISSIONS = PosixFilePermissions.asFileAttribute(
            java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
    private static final java.util.Set<java.nio.file.attribute.PosixFilePermission> PRIVATE_DIRECTORY_PERMISSIONS =
            PosixFilePermissions.fromString("rwx------");
    private final Path root;
    private final ImportLimits limits;
    private final Semaphore uploadSlots;

    public PrivateImportFileStore(@Value("${app.import.storage-directory}") String root, ImportLimits limits) throws IOException {
        this(Path.of(root), limits);
    }

    public PrivateImportFileStore(Path root, ImportLimits limits) throws IOException {
        if (!root.isAbsolute()) throw new IllegalArgumentException("Import storage directory must be absolute");
        Files.createDirectories(root);
        this.root = root.toRealPath();
        this.limits = limits;
        if (limits.maxConcurrentUploadsPerProcess() < 1) {
            throw new IllegalArgumentException("Import upload concurrency limit must be positive");
        }
        this.uploadSlots = new Semaphore(limits.maxConcurrentUploadsPerProcess());
        if (Files.getFileAttributeView(this.root, PosixFileAttributeView.class) != null) {
            Files.setPosixFilePermissions(this.root, PRIVATE_DIRECTORY_PERMISSIONS);
        }
    }

    @Override
    public StoredImportFile store(InputStream input, long limit) throws IOException {
        if (limit <= 0 || limit > limits.maxUploadBytes()) throw new BadRequestException("Invalid import upload limit");
        if (!uploadSlots.tryAcquire()) throw new TooManyRequestsException("Too many concurrent import uploads");
        Path temporary = null;
        try {
            if (Files.getFileStore(root).getUsableSpace() < limit) throw new IOException("Insufficient import storage space");
            temporary = Files.getFileAttributeView(root, PosixFileAttributeView.class) != null
                    ? Files.createTempFile(root, ".upload-", ".part", PRIVATE_FILE_PERMISSIONS)
                    : Files.createTempFile(root, ".upload-", ".part");
            MessageDigest digest = sha256();
            long count = 0;
            try (OutputStream output = Files.newOutputStream(temporary, StandardOpenOption.WRITE)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    if (read == 0) continue;
                    if (read > limit - count) throw new BadRequestException("Import upload exceeds byte limit");
                    output.write(buffer, 0, read);
                    digest.update(buffer, 0, read);
                    count += read;
                }
            }
            String key = UUID.randomUUID().toString();
            Files.move(temporary, root.resolve(key), StandardCopyOption.ATOMIC_MOVE);
            temporary = null;
            return new StoredImportFile(key, HexFormat.of().formatHex(digest.digest()), count);
        } catch (IOException | RuntimeException | Error ex) {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException cleanupException) {
                    ex.addSuppressed(cleanupException);
                }
            }
            throw ex;
        } finally {
            uploadSlots.release();
        }
    }

    @Override
    public InputStream open(String generatedKey) throws IOException {
        return Files.newInputStream(pathForReading(generatedKey), StandardOpenOption.READ);
    }

    public Path pathForReading(String generatedKey) throws IOException {
        Path path = resolve(generatedKey);
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || !path.toRealPath().startsWith(root)) {
            throw new BadRequestException("Invalid import storage key");
        }
        return path;
    }

    @Override
    public void delete(String generatedKey) throws IOException {
        Path path = resolve(generatedKey);
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || !path.toRealPath().startsWith(root)) {
                throw new BadRequestException("Invalid import storage key");
            }
            Files.deleteIfExists(path);
        }
    }

    private Path resolve(String key) {
        if (key == null || !key.matches("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}")) {
            throw new BadRequestException("Invalid import storage key");
        }
        Path path = root.resolve(key).normalize();
        if (!path.getParent().equals(root)) throw new BadRequestException("Invalid import storage key");
        return path;
    }

    private MessageDigest sha256() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }
}
