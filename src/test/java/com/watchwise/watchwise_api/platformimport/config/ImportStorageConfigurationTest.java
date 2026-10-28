package com.watchwise.watchwise_api.platformimport.config;

import com.watchwise.watchwise_api.platformimport.service.ImportLimits;
import com.watchwise.watchwise_api.platformimport.storage.ImportFileStore;
import com.watchwise.watchwise_api.platformimport.storage.R2ImportFileStore;
import com.watchwise.watchwise_api.platformimport.storage.ImportTemporaryFiles;
import com.watchwise.watchwise_api.platformimport.storage.PrivateImportFileStore;
import com.watchwise.watchwise_api.platformimport.storage.S3StubServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ImportStorageConfigurationTest {
    @TempDir
    Path root;

    @Test
    void r2ModeCreatesOnePrivateStoreWithoutMakingStartupRequests() throws Exception {
        try (var fixture = S3StubServer.start("test-imports");
             var temporary = temporaryFiles("r2-work")) {
            runner("r2", fixture.endpoint().toString(), temporary)
                    .run(context -> {
                        assertThat(context).hasNotFailed();
                        assertThat(context.getBeansOfType(ImportFileStore.class)).hasSize(1);
                        assertThat(context.getBean(ImportFileStore.class)).isInstanceOf(R2ImportFileStore.class);
                        assertThat(fixture.requests()).isEmpty();
                    });
        }
    }

    @Test
    void localModeDoesNotRequireR2Credentials() throws Exception {
        Path localStorage = root.resolve("local-imports");
        try (var temporary = temporaryFiles("local-work")) {
            runner("local", "", temporary, "app.import.storage-directory=" + localStorage)
                    .run(context -> {
                        assertThat(context).hasNotFailed();
                        assertThat(context.getBeansOfType(ImportFileStore.class)).hasSize(1);
                        assertThat(context.getBean(ImportFileStore.class)).isInstanceOf(PrivateImportFileStore.class);
                        assertThat(localStorage).isDirectory();
                    });
        }
    }

    @Test
    void rejectsMissingR2CredentialsWithoutEchoingOtherSecretValues() throws Exception {
        try (var temporary = temporaryFiles("missing-credentials")) {
            runner("r2", "https://127.0.0.1:9000", temporary,
                    "app.import.r2.access-key-id=",
                    "app.import.r2.secret-access-key=must-not-appear-in-the-error")
                    .run(context -> {
                        assertThat(context).hasFailed();
                        String failure = rootCause(context.getStartupFailure()).getMessage();
                        assertThat(failure).contains("app.import.r2.access-key-id");
                        assertThat(failure).doesNotContain("must-not-appear-in-the-error");
                    });
        }
    }

    @Test
    void rejectsAnHttpEndpointInProductionBeforeAnyNetworkRequest() throws Exception {
        try (var fixture = S3StubServer.start("test-imports");
             var temporary = temporaryFiles("production-http")) {
            runner("r2", fixture.endpoint().toString(), temporary,
                    "spring.profiles.active=prod")
                    .run(context -> {
                        assertThat(context).hasFailed();
                        assertThat(rootCause(context.getStartupFailure()).getMessage())
                                .contains("app.import.r2.endpoint");
                        assertThat(fixture.requests()).isEmpty();
                    });
        }
    }

    @Test
    void rejectsUnknownStorageBackendInsteadOfStartingWithoutAStore() throws Exception {
        try (var temporary = temporaryFiles("invalid-backend")) {
            runner("remote", "", temporary)
                    .run(context -> {
                        assertThat(context).hasFailed();
                        assertThat(rootCause(context.getStartupFailure()).getMessage())
                                .contains("Invalid app.import.storage.backend");
                    });
        }
    }

    private ApplicationContextRunner runner(String backend, String endpoint,
                                            ImportTemporaryFiles temporary, String... extraProperties) {
        String[] properties = new String[extraProperties.length + 6];
        properties[0] = "app.import.storage.backend=" + backend;
        properties[1] = "app.import.storage-directory=" + root.resolve("local-imports");
        properties[2] = "app.import.r2.endpoint=" + endpoint;
        properties[3] = "app.import.r2.bucket=test-imports";
        properties[4] = "app.import.r2.access-key-id=fixture-access";
        properties[5] = "app.import.r2.secret-access-key=fixture-secret";
        System.arraycopy(extraProperties, 0, properties, 6, extraProperties.length);

        return new ApplicationContextRunner()
                .withUserConfiguration(ImportStorageConfiguration.class, PrivateImportFileStore.class)
                .withBean(ImportLimits.class, ImportLimits::new)
                .withBean(ImportTemporaryFiles.class, () -> temporary)
                .withPropertyValues(properties);
    }

    private ImportTemporaryFiles temporaryFiles(String name) throws Exception {
        ImportLimits limits = new ImportLimits();
        limits.setMaxUploadBytes(1024 * 1024);
        return new ImportTemporaryFiles(root.resolve(name), limits.maxUploadBytes(), 1024 * 1024 + 1024, 1, 1);
    }

    private Throwable rootCause(Throwable failure) {
        Throwable current = failure;
        while (current.getCause() != null) current = current.getCause();
        return current;
    }
}
