package com.watchwise.watchwise_api.platformimport.storage;

import com.watchwise.watchwise_api.common.exception.ServiceUnavailableException;
import com.watchwise.watchwise_api.platformimport.model.ImportArtifactReference;
import com.watchwise.watchwise_api.platformimport.service.ImportLimits;
import org.springframework.stereotype.Component;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

@Component
public final class ImportArtifactReader {
    private static final int COPY_BUFFER_BYTES = 8 * 1024;

    private final ImportFileStore store;
    private final ImportTemporaryFiles temporary;
    private final ImportLimits limits;

    public ImportArtifactReader(ImportFileStore store, ImportTemporaryFiles temporary, ImportLimits limits) {
        this.store = store;
        this.temporary = temporary;
        this.limits = limits;
    }

    public ReadableImportArtifact open(ImportArtifactReference reference) {
        if (reference == null) throw new IllegalArgumentException("Import artifact reference is required");
        long configuredLimit = limits.maxUploadBytes();
        if (configuredLimit <= 0 || reference.bytes() > configuredLimit) {
            throw corrupt("Import artifact size exceeds the configured limit");
        }

        TemporaryImportFile materialized = temporary.createRead(Math.max(1, reference.bytes()));
        try {
            MessageDigest digest = sha256();
            long bytes;
            try (InputStream input = store.open(reference.key());
                 OutputStream output = Files.newOutputStream(materialized.path(),
                         StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                bytes = copyAndHash(input, output, digest, reference.bytes());
            }

            byte[] expectedDigest = HexFormat.of().parseHex(reference.sha256());
            byte[] actualDigest = digest.digest();
            if (bytes != reference.bytes() || !MessageDigest.isEqual(expectedDigest, actualDigest)) {
                throw corrupt("Import artifact does not match its stored reference");
            }
            return new MaterializedImportArtifact(materialized, limits.maxConcurrentArtifactReadsPerProcess());
        } catch (IOException exception) {
            materialized.close();
            throw new ImportStorageUnavailableException(exception);
        } catch (RuntimeException | Error failure) {
            materialized.close();
            throw failure;
        }
    }

    private long copyAndHash(InputStream input, OutputStream output, MessageDigest digest,
                             long expectedBytes) throws IOException {
        byte[] buffer = new byte[COPY_BUFFER_BYTES];
        long total = 0;
        for (int count; (count = input.read(buffer)) != -1;) {
            if (count == 0) continue;
            if (count > expectedBytes - total) throw corrupt("Import artifact exceeds its stored size");
            output.write(buffer, 0, count);
            digest.update(buffer, 0, count);
            total += count;
        }
        return total;
    }

    private ImportArtifactIntegrityException corrupt(String message) {
        return new ImportArtifactIntegrityException(ImportArtifactIntegrityException.Reason.CORRUPT,
                new IllegalStateException(message));
    }

    private MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static final class MaterializedImportArtifact implements ReadableImportArtifact {
        private final TemporaryImportFile materialized;
        private final int maxOpenStreams;
        private final Set<TrackedStream> childStreams = Collections.newSetFromMap(new IdentityHashMap<>());
        private boolean closed;

        private MaterializedImportArtifact(TemporaryImportFile materialized, int maxOpenStreams) {
            this.materialized = materialized;
            this.maxOpenStreams = maxOpenStreams;
        }

        @Override
        public synchronized Path path() {
            ensureOpen();
            return materialized.path();
        }

        @Override
        public synchronized InputStream openStream() throws IOException {
            ensureOpen();
            if (childStreams.size() >= maxOpenStreams) {
                throw new ServiceUnavailableException("Import artifact stream capacity is full");
            }
            TrackedStream stream = new TrackedStream(Files.newInputStream(materialized.path(), StandardOpenOption.READ));
            childStreams.add(stream);
            return stream;
        }

        private void ensureOpen() {
            if (closed) throw new IllegalStateException("Materialized import artifact is closed");
        }

        private void release(TrackedStream stream) {
            synchronized (this) {
                childStreams.remove(stream);
            }
        }

        @Override
        public void close() {
            List<TrackedStream> streams;
            synchronized (this) {
                if (closed) return;
                closed = true;
                streams = new ArrayList<>(childStreams);
            }

            IOException closeFailure = null;
            for (TrackedStream stream : streams) {
                try {
                    stream.close();
                } catch (IOException exception) {
                    if (closeFailure == null) closeFailure = exception;
                    else closeFailure.addSuppressed(exception);
                }
            }
            materialized.close();
            if (closeFailure != null) throw new ImportStorageUnavailableException(closeFailure);
        }

        private final class TrackedStream extends FilterInputStream {
            private boolean streamClosed;

            private TrackedStream(InputStream input) {
                super(input);
            }

            @Override
            public void close() throws IOException {
                synchronized (this) {
                    if (streamClosed) return;
                    streamClosed = true;
                }
                try {
                    super.close();
                } finally {
                    release(this);
                }
            }
        }
    }
}
