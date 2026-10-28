package com.watchwise.watchwise_api.platformimport.storage;

import com.watchwise.watchwise_api.common.exception.BadRequestException;
import com.watchwise.watchwise_api.common.exception.ServiceUnavailableException;
import com.watchwise.watchwise_api.common.exception.TooManyRequestsException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

public final class ImportTemporaryFiles implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(ImportTemporaryFiles.class);
    private static final String RUN_PREFIX = "run-";
    private static final Set<PosixFilePermission> DIRECTORY_PERMISSIONS =
            PosixFilePermissions.fromString("rwx------");
    private static final FileAttribute<?> DIRECTORY_ATTRIBUTE =
            PosixFilePermissions.asFileAttribute(DIRECTORY_PERMISSIONS);
    private static final FileAttribute<?> FILE_ATTRIBUTE =
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"));

    private final Path root;
    private final Path runDirectory;
    private final Path multipartDirectory;
    private final Path uploadDirectory;
    private final Path artifactDirectory;
    private final long maxUploadBytes;
    private final long maxRequestBytes;
    private final long maxReservedBytes;
    private final int maxOutstandingFiles;
    private final Semaphore uploadSlots;
    private final Semaphore multipartSlots;
    private final Semaphore readSlots;
    private final Object monitor = new Object();
    private final Map<Path, FileReservation> outstandingFiles = new HashMap<>();
    private final Map<UUID, MultipartReservation> multipartReservations = new HashMap<>();
    private FileChannel lockChannel;
    private FileLock processLock;
    private long reservedBytes;
    private int pendingCreations;
    private boolean closed;

    public ImportTemporaryFiles(Path root, long maxUploadBytes, long maxRequestBytes,
                                int uploadSlots, int readSlots) throws IOException {
        if (root == null || !root.isAbsolute()) {
            throw new IllegalArgumentException("Import temporary directory must be absolute");
        }
        if (maxUploadBytes <= 0 || maxRequestBytes < maxUploadBytes || uploadSlots < 1 || readSlots < 1) {
            throw new IllegalArgumentException("Import temporary limits and concurrency must be positive");
        }
        this.root = root.normalize();
        this.maxUploadBytes = maxUploadBytes;
        this.maxRequestBytes = maxRequestBytes;
        this.maxReservedBytes = maximumReservedBytes(maxUploadBytes, maxRequestBytes, uploadSlots, readSlots);
        try {
            this.maxOutstandingFiles = Math.addExact(uploadSlots, readSlots);
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("Import temporary concurrency is too large", exception);
        }
        this.uploadSlots = new Semaphore(uploadSlots);
        this.multipartSlots = new Semaphore(uploadSlots);
        this.readSlots = new Semaphore(readSlots);

        Path globalTemporaryDirectory = Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize();
        if (this.root.getParent() == null || this.root.equals(globalTemporaryDirectory)
                || globalTemporaryDirectory.startsWith(this.root)) {
            throw new IllegalArgumentException("Import temporary directory must be dedicated");
        }
        createPrivateDirectoryTree(this.root);
        rejectSymlinkAncestors(this.root);
        reapAbandonedRuns();

        Path createdRunDirectory = this.root.resolve(RUN_PREFIX + UUID.randomUUID());
        createPrivateDirectory(createdRunDirectory);
        Path createdMultipartDirectory = createdRunDirectory.resolve("multipart");
        Path createdUploadDirectory = createdRunDirectory.resolve("uploads");
        Path createdArtifactDirectory = createdRunDirectory.resolve("artifacts");
        Path lockPath = createdRunDirectory.resolve("session.lock");
        FileChannel openedChannel = null;
        FileLock acquiredLock = null;
        try {
            createPrivateDirectory(createdMultipartDirectory);
            createPrivateDirectory(createdUploadDirectory);
            createPrivateDirectory(createdArtifactDirectory);
            createPrivateFile(lockPath);
            openedChannel = FileChannel.open(lockPath, StandardOpenOption.READ, StandardOpenOption.WRITE);
            try {
                acquiredLock = openedChannel.tryLock();
            } catch (OverlappingFileLockException exception) {
                throw new IllegalStateException("Import temporary run directory is already active", exception);
            }
            if (acquiredLock == null) {
                throw new IllegalStateException("Import temporary run directory is already active");
            }
        } catch (IOException | RuntimeException failure) {
            if (acquiredLock != null) {
                try {
                    acquiredLock.release();
                } catch (IOException cleanupFailure) {
                    failure.addSuppressed(cleanupFailure);
                }
            }
            if (openedChannel != null) {
                try {
                    openedChannel.close();
                } catch (IOException cleanupFailure) {
                    failure.addSuppressed(cleanupFailure);
                }
            }
            try {
                deleteTreeNoFollow(createdRunDirectory);
            } catch (IOException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
        this.runDirectory = createdRunDirectory;
        this.multipartDirectory = createdMultipartDirectory;
        this.uploadDirectory = createdUploadDirectory;
        this.artifactDirectory = createdArtifactDirectory;
        this.lockChannel = openedChannel;
        this.processLock = acquiredLock;
    }

    public Path multipartDirectory() {
        ensureOpen();
        return multipartDirectory;
    }

    public Path rootDirectory() {
        ensureOpen();
        return root;
    }

    public MultipartUploadReservation admitMultipart() {
        settleMultipartFiles();
        synchronized (monitor) {
            ensureOpen();
            if (!multipartSlots.tryAcquire()) {
                throw new TooManyRequestsException("Too many concurrent import uploads");
            }
            try {
                reserve(maxRequestBytes, true);
            } catch (RuntimeException exception) {
                multipartSlots.release();
                throw exception;
            }
            MultipartReservation reservation = new MultipartReservation(UUID.randomUUID());
            multipartReservations.put(reservation.id(), reservation);
            return reservation;
        }
    }

    public TemporaryImportFile createUpload(long limit) {
        if (limit <= 0 || limit > maxUploadBytes) {
            throw new BadRequestException("Invalid import upload limit");
        }
        return createTemporary(uploadDirectory, limit, uploadSlots, true);
    }

    public TemporaryImportFile createRead(long limit) {
        if (limit <= 0 || limit > maxUploadBytes) {
            throw new BadRequestException("Invalid import artifact read limit");
        }
        return createTemporary(artifactDirectory, limit, readSlots, false);
    }

    public long reservedBytes() {
        synchronized (monitor) {
            return reservedBytes;
        }
    }

    public int reapAbandoned() {
        ensureNotClosed();
        int removed = reapOutstandingFiles();
        settleMultipartFiles();
        return removed + reapAbandonedRuns();
    }

    @Override
    public void close() {
        reapOutstandingFiles();
        settleMultipartFiles();
        synchronized (monitor) {
            if (closed) return;
            if (pendingCreations != 0 || !outstandingFiles.isEmpty() || !multipartReservations.isEmpty()) {
                throw new IllegalStateException("Import temporary files are still in use");
            }
            closed = true;
        }
        closeLock();
        deleteOwnRunDirectory();
    }

    private TemporaryImportFile createTemporary(Path directory, long limit, Semaphore slots, boolean upload) {
        synchronized (monitor) {
            ensureOpen();
        }
        reapOutstandingFiles();
        if (!slots.tryAcquire()) {
            if (upload) throw new TooManyRequestsException("Too many concurrent import uploads");
            throw new ServiceUnavailableException("Import artifact processing is busy");
        }
        synchronized (monitor) {
            try {
                ensureOpen();
                if (outstandingFiles.size() + pendingCreations >= maxOutstandingFiles) {
                    throw new ServiceUnavailableException("Import temporary cleanup is pending");
                }
                reserve(limit, false);
                pendingCreations++;
            } catch (RuntimeException exception) {
                slots.release();
                throw exception;
            }
        }

        Path path = null;
        try {
            if (Files.getFileStore(directory).getUsableSpace() < limit) {
                throw new ServiceUnavailableException("Import temporary storage is full");
            }
            path = Files.getFileAttributeView(directory, PosixFileAttributeView.class) != null
                    ? Files.createTempFile(directory, ".import-", ".part", FILE_ATTRIBUTE)
                    : Files.createTempFile(directory, ".import-", ".part");
            FileReservation reservation = new FileReservation(path, limit, slots, true);
            synchronized (monitor) {
                pendingCreations--;
                outstandingFiles.put(path, reservation);
            }
            return reservation;
        } catch (IOException | RuntimeException failure) {
            if (path != null) {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException cleanupFailure) {
                    failure.addSuppressed(cleanupFailure);
                    FileReservation orphan = new FileReservation(path, limit, slots, false);
                    synchronized (monitor) {
                        pendingCreations--;
                        outstandingFiles.put(path, orphan);
                    }
                    orphan.releaseSlot();
                    throw new ServiceUnavailableException("Import temporary cleanup is unavailable");
                }
            }
            synchronized (monitor) {
                pendingCreations--;
                reservedBytes -= limit;
            }
            slots.release();
            if (failure instanceof ServiceUnavailableException unavailable) throw unavailable;
            throw new ServiceUnavailableException("Import temporary storage is unavailable");
        }
    }

    private void reserve(long bytes, boolean multipart) {
        final long next;
        try {
            next = Math.addExact(reservedBytes, bytes);
        } catch (ArithmeticException exception) {
            throw new ServiceUnavailableException("Import temporary storage budget is exceeded");
        }
        if (next > maxReservedBytes) {
            if (multipart) throw new TooManyRequestsException("Import temporary capacity is full");
            throw new ServiceUnavailableException("Import temporary storage is busy");
        }
        reservedBytes = next;
    }

    private int reapOutstandingFiles() {
        List<FileReservation> snapshot;
        synchronized (monitor) {
            snapshot = outstandingFiles.values().stream().filter(FileReservation::isInactive).toList();
        }
        int removed = 0;
        for (FileReservation reservation : snapshot) {
            if (reservation.tryDelete()) removed++;
        }
        return removed;
    }

    private void settleMultipartFiles() {
        synchronized (monitor) {
            if (multipartReservations.values().stream().anyMatch(MultipartReservation::isActive)) return;
            try {
                deleteDirectoryContents(multipartDirectory);
            } catch (IOException exception) {
                log.warn("Could not clear multipart staging; its disk reservation is retained", exception);
                return;
            }
            long bytes = multipartReservations.values().stream().mapToLong(MultipartReservation::bytes).sum();
            if (bytes > 0) reservedBytes -= bytes;
            multipartReservations.clear();
        }
    }

    private void releaseMultipart(UUID id) {
        synchronized (monitor) {
            MultipartReservation reservation = multipartReservations.get(id);
            if (reservation == null || !reservation.releaseSlot()) return;
            multipartSlots.release();
            settleMultipartFiles();
        }
    }

    private int reapAbandonedRuns() {
        int removed = 0;
        try (var children = Files.list(root)) {
            for (Path child : children.toList()) {
                if (isReapMarker(child) && !Files.isSymbolicLink(child)) {
                    if (recoverMarkedRun(child)) removed++;
                    continue;
                }
                if (!isRunDirectory(child) || child.equals(runDirectory) || Files.isSymbolicLink(child)) continue;
                if (tryDeleteAbandonedRun(child)) removed++;
            }
        } catch (IOException exception) {
            throw new ServiceUnavailableException("Import temporary cleanup is unavailable");
        }
        return removed;
    }

    private boolean tryDeleteAbandonedRun(Path abandoned) {
        Path abandonedLock = abandoned.resolve("session.lock");
        if (Files.isSymbolicLink(abandonedLock) || !Files.isRegularFile(abandonedLock, LinkOption.NOFOLLOW_LINKS)) {
            return false;
        }
        String runId = abandoned.getFileName().toString().substring(RUN_PREFIX.length());
        Path marker = root.resolve("reap-" + runId + ".lock");
        if (Files.exists(marker, LinkOption.NOFOLLOW_LINKS)) return false;

        FileChannel runChannel = null;
        FileLock runLock = null;
        FileChannel markerChannel = null;
        FileLock markerLock = null;
        boolean markerCreated = false;
        boolean runReleased = false;
        try {
            runChannel = FileChannel.open(abandonedLock, StandardOpenOption.WRITE);
            try {
                runLock = runChannel.tryLock();
            } catch (OverlappingFileLockException exception) {
                return false;
            }
            if (runLock == null || Files.exists(marker, LinkOption.NOFOLLOW_LINKS)) {
                log.warn("Could not claim an inactive import temporary run for cleanup");
                return false;
            }

            createPrivateFile(marker);
            markerCreated = true;
            markerChannel = FileChannel.open(marker, StandardOpenOption.READ, StandardOpenOption.WRITE);
            try {
                markerLock = markerChannel.tryLock();
            } catch (OverlappingFileLockException exception) {
                return false;
            }
            if (markerLock == null) {
                log.warn("Could not lock an import temporary cleanup marker");
                return false;
            }

            runLock.release();
            runLock = null;
            runChannel.close();
            runChannel = null;
            runReleased = true;
            deleteTreeNoFollow(abandoned);
        } catch (IOException exception) {
            log.warn("Could not reclaim an inactive import temporary run", exception);
            return false;
        } finally {
            if (runLock != null) {
                try {
                    runLock.release();
                } catch (IOException exception) {
                    log.warn("Could not release an inactive import temporary lock");
                }
            }
            if (runChannel != null) {
                try {
                    runChannel.close();
                } catch (IOException exception) {
                    log.warn("Could not close an inactive import temporary lock channel");
                }
            }
            if (markerLock != null) {
                try {
                    markerLock.release();
                } catch (IOException exception) {
                    log.warn("Could not release an import temporary cleanup marker");
                }
            }
            if (markerChannel != null) {
                try {
                    markerChannel.close();
                } catch (IOException exception) {
                    log.warn("Could not close an import temporary cleanup marker");
                }
            }
            if (markerCreated && runReleased) {
                try {
                    Files.deleteIfExists(marker);
                } catch (IOException exception) {
                    log.warn("Could not remove an import temporary cleanup marker");
                }
            } else if (markerCreated && runLock != null) {
                try {
                    Files.deleteIfExists(marker);
                } catch (IOException exception) {
                    log.warn("Could not remove an import temporary cleanup marker");
                }
            }
        }
        return runReleased;
    }

    private boolean recoverMarkedRun(Path marker) {
        String markerName = marker.getFileName().toString();
        String runId = markerName.substring("reap-".length(), markerName.length() - ".lock".length());
        Path abandoned = root.resolve(RUN_PREFIX + runId);
        FileChannel markerChannel = null;
        FileLock markerLock = null;
        FileChannel runChannel = null;
        FileLock runLock = null;
        boolean recovered = false;
        try {
            markerChannel = FileChannel.open(marker, StandardOpenOption.READ, StandardOpenOption.WRITE);
            try {
                markerLock = markerChannel.tryLock();
            } catch (OverlappingFileLockException exception) {
                return false;
            }
            if (markerLock == null) return false;

            Path abandonedLock = abandoned.resolve("session.lock");
            if (Files.exists(abandonedLock, LinkOption.NOFOLLOW_LINKS)) {
                if (Files.isSymbolicLink(abandonedLock)
                        || !Files.isRegularFile(abandonedLock, LinkOption.NOFOLLOW_LINKS)) return false;
                runChannel = FileChannel.open(abandonedLock, StandardOpenOption.WRITE);
                try {
                    runLock = runChannel.tryLock();
                } catch (OverlappingFileLockException exception) {
                    return false;
                }
                if (runLock == null) return false;
                runLock.release();
                runLock = null;
                runChannel.close();
                runChannel = null;
            }
            if (Files.exists(abandoned, LinkOption.NOFOLLOW_LINKS)) deleteTreeNoFollow(abandoned);
            recovered = true;
            return true;
        } catch (IOException exception) {
            log.warn("Could not finish recovery of an import temporary run", exception);
            return false;
        } finally {
            if (runLock != null) {
                try {
                    runLock.release();
                } catch (IOException exception) {
                    log.warn("Could not release a recovered import temporary lock");
                }
            }
            if (runChannel != null) {
                try {
                    runChannel.close();
                } catch (IOException exception) {
                    log.warn("Could not close a recovered import temporary lock channel");
                }
            }
            if (markerLock != null) {
                try {
                    markerLock.release();
                } catch (IOException exception) {
                    log.warn("Could not release a recovered import temporary cleanup marker");
                }
            }
            if (markerChannel != null) {
                try {
                    markerChannel.close();
                } catch (IOException exception) {
                    log.warn("Could not close a recovered import temporary cleanup marker");
                }
            }
            if (recovered) {
                try {
                    Files.deleteIfExists(marker);
                } catch (IOException exception) {
                    log.warn("Could not remove a recovered import temporary cleanup marker");
                }
            }
        }
    }

    private void deleteDirectoryContents(Path directory) throws IOException {
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) return;
        if (Files.isSymbolicLink(directory) || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Import multipart staging path is invalid");
        }
        try (var entries = Files.list(directory)) {
            for (Path entry : entries.toList()) deleteTreeNoFollow(entry);
        }
    }

    private void deleteTreeNoFollow(Path path) throws IOException {
        if (Files.isSymbolicLink(path)) throw new IOException("Import temporary path is a symbolic link");
        if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            try (var children = Files.list(path)) {
                for (Path child : children.toList()) deleteTreeNoFollow(child);
            }
        }
        Files.deleteIfExists(path);
    }

    private void createPrivateDirectoryTree(Path directory) throws IOException {
        rejectSymlinkAncestors(directory);
        if (Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
            verifyPrivateDirectory(directory);
            return;
        }
        Path parent = directory.getParent();
        if (parent == null) throw new IllegalArgumentException("Import temporary directory has no parent");
        if (!Files.exists(parent, LinkOption.NOFOLLOW_LINKS)) createPrivateDirectoryTree(parent);
        try {
            createPrivateDirectory(directory);
        } catch (FileAlreadyExistsException race) {
            verifyPrivateDirectory(directory);
        }
    }

    private void createPrivateDirectory(Path directory) throws IOException {
        if (Files.getFileAttributeView(directory.getParent(), PosixFileAttributeView.class) != null) {
            Files.createDirectory(directory, DIRECTORY_ATTRIBUTE);
        } else {
            Files.createDirectory(directory);
        }
        verifyPrivateDirectory(directory);
    }

    private void createPrivateFile(Path path) throws IOException {
        if (Files.getFileAttributeView(path.getParent(), PosixFileAttributeView.class) != null) {
            Files.createFile(path, FILE_ATTRIBUTE);
        } else {
            Files.createFile(path);
        }
    }

    private void verifyPrivateDirectory(Path directory) throws IOException {
        if (Files.isSymbolicLink(directory) || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("Import temporary path must be a real directory");
        }
        if (Files.getFileAttributeView(directory, PosixFileAttributeView.class) != null
                && !Files.getPosixFilePermissions(directory, LinkOption.NOFOLLOW_LINKS).equals(DIRECTORY_PERMISSIONS)) {
            throw new IllegalStateException("Import temporary directory must have private permissions");
        }
    }

    private void rejectSymlinkAncestors(Path path) {
        Path current = path.getRoot();
        for (Path part : path) {
            current = current.resolve(part);
            if (Files.isSymbolicLink(current)) {
                throw new IllegalArgumentException("Import temporary path cannot contain symbolic links");
            }
        }
    }

    private boolean isRunDirectory(Path path) {
        return path.getFileName() != null
                && path.getFileName().toString().matches("run-[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}")
                && Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS);
    }

    private boolean isReapMarker(Path path) {
        return path.getFileName() != null
                && path.getFileName().toString().matches("reap-[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}\\.lock")
                && Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS);
    }

    private void ensureOpen() {
        if (closed) throw new IllegalStateException("Import temporary storage is closed");
    }

    private void ensureNotClosed() {
        synchronized (monitor) {
            ensureOpen();
        }
    }

    private void closeLock() {
        try {
            if (processLock != null && processLock.isValid()) processLock.release();
        } catch (IOException exception) {
            throw new ServiceUnavailableException("Import temporary lock could not be released");
        } finally {
            try {
                if (lockChannel != null) lockChannel.close();
            } catch (IOException exception) {
                log.warn("Could not close import temporary lock channel");
            }
        }
    }

    private void deleteOwnRunDirectory() {
        try {
            deleteTreeNoFollow(runDirectory);
        } catch (IOException exception) {
            throw new ServiceUnavailableException("Import temporary run directory could not be removed");
        }
    }

    private static long maximumReservedBytes(long fileBytes, long requestBytes, int uploads, int reads) {
        try {
            return Math.addExact(
                    Math.multiplyExact((long) uploads, Math.addExact(requestBytes, fileBytes)),
                    Math.multiplyExact((long) reads, fileBytes));
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("Import temporary storage budget is too large", exception);
        }
    }

    private final class FileReservation implements TemporaryImportFile {
        private final Path path;
        private final long bytes;
        private final Semaphore slots;
        private final AtomicBoolean slotReleased = new AtomicBoolean();
        private boolean active;

        private FileReservation(Path path, long bytes, Semaphore slots, boolean active) {
            this.path = path;
            this.bytes = bytes;
            this.slots = slots;
            this.active = active;
        }

        @Override
        public Path path() {
            synchronized (monitor) {
                ensureOpen();
                if (!outstandingFiles.containsKey(path) || !active) {
                    throw new IllegalStateException("Import temporary file is closed");
                }
                return path;
            }
        }

        @Override
        public void close() {
            synchronized (monitor) {
                if (!active) return;
                active = false;
            }
            tryDelete();
        }

        private boolean isInactive() {
            synchronized (monitor) {
                return !active;
            }
        }

        private boolean tryDelete() {
            try {
                Files.deleteIfExists(path);
            } catch (NoSuchFileException ignored) {
            } catch (IOException exception) {
                log.warn("Could not remove a temporary import file; its disk reservation is retained", exception);
                releaseSlot();
                return false;
            }
            synchronized (monitor) {
                if (outstandingFiles.remove(path, this)) reservedBytes -= bytes;
            }
            releaseSlot();
            return true;
        }

        private void releaseSlot() {
            if (slotReleased.compareAndSet(false, true)) slots.release();
        }
    }

    private final class MultipartReservation implements MultipartUploadReservation {
        private final UUID id;
        private boolean active = true;

        private MultipartReservation(UUID id) {
            this.id = id;
        }

        private UUID id() { return id; }
        private long bytes() { return maxRequestBytes; }
        private boolean isActive() { return active; }

        private boolean releaseSlot() {
            if (!active) return false;
            active = false;
            return true;
        }

        @Override
        public void close() {
            releaseMultipart(id);
        }
    }
}
