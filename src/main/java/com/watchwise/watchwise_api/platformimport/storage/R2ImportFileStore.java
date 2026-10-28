package com.watchwise.watchwise_api.platformimport.storage;

import com.watchwise.watchwise_api.common.exception.BadRequestException;
import com.watchwise.watchwise_api.common.exception.ServiceUnavailableException;
import com.watchwise.watchwise_api.common.exception.TooManyRequestsException;
import com.watchwise.watchwise_api.platformimport.config.R2StorageProperties;
import com.watchwise.watchwise_api.platformimport.model.StoredImportFile;
import com.watchwise.watchwise_api.platformimport.service.ImportLimits;
import org.springframework.http.HttpStatus;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public final class R2ImportFileStore implements ImportFileStore {
    private static final int COPY_BUFFER_BYTES = 8 * 1024;
    private static final String UPLOAD_ID_METADATA = "watchwise-upload-id";
    private static final String SHA256_METADATA = "watchwise-sha256";
    private static final String BYTES_METADATA = "watchwise-bytes";

    private final S3Client client;
    private final R2StorageProperties properties;
    private final ImportTemporaryFiles temporaryFiles;
    private final ImportLimits limits;
    private final ScheduledExecutorService deadlineScheduler;

    public R2ImportFileStore(S3Client client, R2StorageProperties properties,
                             ImportTemporaryFiles temporaryFiles, ImportLimits limits,
                             ScheduledExecutorService deadlineScheduler) {
        this.client = client;
        this.properties = properties;
        this.temporaryFiles = temporaryFiles;
        this.limits = limits;
        this.deadlineScheduler = deadlineScheduler;
    }

    @Override
    public StoredImportFile store(InputStream input, long limit, String generatedKey) throws IOException {
        validateKey(generatedKey);
        if (input == null || limit <= 0 || limit > limits.maxUploadBytes()) {
            throw new BadRequestException("Invalid import upload limit");
        }

        long deadline = deadlineAfter(properties.getOperationTimeoutMs());
        String uploadId = UUID.randomUUID().toString();
        try (TemporaryImportFile temporary = temporaryFiles.createUpload(limit)) {
            MessageDigest digest = sha256();
            long bytes = copyBounded(input, temporary.path(), limit, deadline, digest);
            String sha256 = HexFormat.of().formatHex(digest.digest());
            checkDeadline(deadline);

            PutObjectRequest request = PutObjectRequest.builder()
                    .bucket(properties.getBucket())
                    .key(ImportStorageKeys.objectKey(generatedKey))
                    .ifNoneMatch("*")
                    .metadata(Map.of(
                            UPLOAD_ID_METADATA, uploadId,
                            SHA256_METADATA, sha256,
                            BYTES_METADATA, Long.toString(bytes)))
                    .overrideConfiguration(configuration -> applyRemainingTimeout(configuration, deadline))
                    .build();

            try {
                client.putObject(request, RequestBody.fromFile(temporary.path()));
            } catch (S3Exception exception) {
                if (exception.statusCode() == HttpStatus.PRECONDITION_FAILED.value()) {
                    return reconcileWrite(generatedKey, uploadId, sha256, bytes, deadline, exception);
                }
                if (exception.statusCode() >= 500) {
                    return reconcileWrite(generatedKey, uploadId, sha256, bytes, deadline, exception);
                }
                throw unavailable(exception);
            } catch (SdkClientException exception) {
                return reconcileWrite(generatedKey, uploadId, sha256, bytes, deadline, exception);
            }
            return new StoredImportFile(generatedKey, sha256, bytes);
        } catch (ServiceUnavailableException | TooManyRequestsException | ImportStorageKeyCollisionException
                 | ImportArtifactIntegrityException | BadRequestException exception) {
            throw exception;
        } catch (IOException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw unavailable(exception);
        }
    }

    @Override
    public InputStream open(String generatedKey) {
        validateKey(generatedKey);
        long deadline = deadlineAfter(properties.getOperationTimeoutMs());
        try {
            checkDeadline(deadline);
            ResponseInputStream<GetObjectResponse> response = client.getObject(GetObjectRequest.builder()
                    .bucket(properties.getBucket())
                    .key(ImportStorageKeys.objectKey(generatedKey))
                    .overrideConfiguration(configuration -> applyRemainingTimeout(configuration, deadline))
                    .build());
            return new DeadlineInputStream(response, deadline, deadlineScheduler);
        } catch (S3Exception exception) {
            if (isMissing(exception)) {
                throw new ImportArtifactIntegrityException(
                        ImportArtifactIntegrityException.Reason.MISSING, exception);
            }
            throw unavailable(exception);
        } catch (SdkClientException exception) {
            throw unavailable(exception);
        }
    }

    @Override
    public void validateKey(String generatedKey) {
        ImportStorageKeys.validate(generatedKey);
    }

    @Override
    public void delete(String generatedKey) {
        validateKey(generatedKey);
        long deadline = deadlineAfter(properties.getOperationTimeoutMs());
        try {
            client.deleteObject(DeleteObjectRequest.builder()
                    .bucket(properties.getBucket())
                    .key(ImportStorageKeys.objectKey(generatedKey))
                    .overrideConfiguration(configuration -> applyRemainingTimeout(configuration, deadline))
                    .build());
        } catch (S3Exception exception) {
            if (isMissing(exception)) return;
            throw unavailable(exception);
        } catch (SdkClientException exception) {
            throw unavailable(exception);
        }
    }

    private StoredImportFile reconcileWrite(String generatedKey, String uploadId, String sha256,
                                             long bytes, long deadline, Throwable putFailure) {
        try {
            checkDeadline(deadline);
            HeadObjectResponse head = client.headObject(HeadObjectRequest.builder()
                    .bucket(properties.getBucket())
                    .key(ImportStorageKeys.objectKey(generatedKey))
                    .overrideConfiguration(configuration -> applyRemainingTimeout(configuration, deadline))
                    .build());
            Map<String, String> metadata = head.metadata();
            if (metadata != null && uploadId.equals(metadata.get(UPLOAD_ID_METADATA))
                    && sha256.equals(metadata.get(SHA256_METADATA))
                    && Long.toString(bytes).equals(metadata.get(BYTES_METADATA))) {
                return new StoredImportFile(generatedKey, sha256, bytes);
            }
            throw new ImportStorageKeyCollisionException();
        } catch (S3Exception exception) {
            if (isMissing(exception)) {
                throw unavailable(putFailure);
            }
            throw unavailable(exception);
        } catch (SdkClientException | ImportStorageUnavailableException exception) {
            throw unavailable(exception);
        }
    }

    private long copyBounded(InputStream input, java.nio.file.Path target, long limit,
                             long deadline, MessageDigest digest) throws IOException {
        long count = 0;
        byte[] buffer = new byte[COPY_BUFFER_BYTES];
        try (OutputStream output = Files.newOutputStream(target, StandardOpenOption.WRITE)) {
            for (int read; (read = input.read(buffer)) != -1;) {
                checkDeadline(deadline);
                if (read == 0) continue;
                if (read > limit - count) throw new BadRequestException("Import upload exceeds byte limit");
                output.write(buffer, 0, read);
                digest.update(buffer, 0, read);
                count += read;
            }
        }
        return count;
    }

    private void applyRemainingTimeout(software.amazon.awssdk.awscore.AwsRequestOverrideConfiguration.Builder builder,
                                       long deadline) {
        long remaining = remainingNanos(deadline);
        Duration timeout = Duration.ofNanos(remaining);
        Duration attemptTimeout = Duration.ofMillis(properties.getAttemptTimeoutMs());
        if (timeout.compareTo(attemptTimeout) < 0) attemptTimeout = timeout;
        builder.apiCallTimeout(timeout).apiCallAttemptTimeout(attemptTimeout);
    }

    private boolean isMissing(S3Exception exception) {
        return exception instanceof NoSuchKeyException || exception.statusCode() == HttpStatus.NOT_FOUND.value();
    }

    private ImportStorageUnavailableException unavailable(Throwable cause) {
        return new ImportStorageUnavailableException(cause);
    }

    private long deadlineAfter(long timeoutMs) {
        return System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
    }

    private long remainingNanos(long deadline) {
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) throw unavailable(new IOException("Import storage operation deadline exceeded"));
        return remaining;
    }

    private void checkDeadline(long deadline) {
        remainingNanos(deadline);
    }

    private MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
