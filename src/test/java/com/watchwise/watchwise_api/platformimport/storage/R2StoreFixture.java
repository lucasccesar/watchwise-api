package com.watchwise.watchwise_api.platformimport.storage;

import com.watchwise.watchwise_api.platformimport.config.ImportStorageConfiguration;
import com.watchwise.watchwise_api.platformimport.service.ImportLimits;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

final class R2StoreFixture implements AutoCloseable {
    private final S3StubServer server;
    private final ImportTemporaryFiles temporary;
    private final AnnotationConfigApplicationContext context;

    private R2StoreFixture(S3StubServer server, ImportTemporaryFiles temporary,
                           AnnotationConfigApplicationContext context) {
        this.server = server;
        this.temporary = temporary;
        this.context = context;
    }

    static R2StoreFixture start(Path root) throws IOException {
        return start(root, Duration.ofSeconds(20));
    }

    static R2StoreFixture start(Path root, Duration operationTimeout) throws IOException {
        S3StubServer server = S3StubServer.start("test-imports");
        ImportLimits limits = new ImportLimits();
        ImportTemporaryFiles temporary = new ImportTemporaryFiles(root.resolve("r2-work"),
                limits.maxUploadBytes(), 51L * 1024 * 1024, limits.maxConcurrentUploadsPerProcess(),
                limits.maxConcurrentArtifactReadsPerProcess());
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        long operationTimeoutMs = operationTimeout.toMillis();
        long attemptTimeoutMs = Math.min(10_000, operationTimeoutMs);
        long connectionTimeoutMs = Math.min(3_000, operationTimeoutMs);
        long socketTimeoutMs = Math.min(5_000, operationTimeoutMs);
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("r2-fixture", Map.of(
                "app.import.storage.backend", "r2",
                "app.import.storage-directory", root.resolve("unused-local-storage").toString(),
                "app.import.r2.endpoint", server.endpoint().toString(),
                "app.import.r2.bucket", "test-imports",
                "app.import.r2.access-key-id", "fixture-access",
                "app.import.r2.secret-access-key", "fixture-secret",
                "app.import.r2.operation-timeout-ms", Long.toString(operationTimeoutMs),
                "app.import.r2.attempt-timeout-ms", Long.toString(attemptTimeoutMs),
                "app.import.r2.connection-timeout-ms", Long.toString(connectionTimeoutMs),
                "app.import.r2.socket-timeout-ms", Long.toString(socketTimeoutMs))));
        context.registerBean(ImportLimits.class, () -> limits);
        context.registerBean(ImportTemporaryFiles.class, () -> temporary);
        context.register(ImportStorageConfiguration.class, PrivateImportFileStore.class);
        context.refresh();
        return new R2StoreFixture(server, temporary, context);
    }

    ImportFileStore store() {
        return context.getBean(ImportFileStore.class);
    }

    S3StubServer server() {
        return server;
    }

    ImportTemporaryFiles temporary() {
        return temporary;
    }

    String objectPath(String generatedKey) {
        return server.objectPath(generatedKey);
    }

    @Override
    public void close() {
        try {
            context.close();
        } finally {
            try {
                temporary.close();
            } finally {
                server.close();
            }
        }
    }
}
