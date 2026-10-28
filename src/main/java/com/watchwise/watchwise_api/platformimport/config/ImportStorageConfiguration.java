package com.watchwise.watchwise_api.platformimport.config;

import com.watchwise.watchwise_api.platformimport.service.ImportLimits;
import com.watchwise.watchwise_api.platformimport.storage.ImportFileStore;
import com.watchwise.watchwise_api.platformimport.storage.ImportTemporaryFiles;
import com.watchwise.watchwise_api.platformimport.storage.R2ImportFileStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;

import java.net.URI;
import java.time.Duration;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(R2StorageProperties.class)
public class ImportStorageConfiguration {
    @Bean
    ImportStorageBackend importStorageBackend(
            @Value("${app.import.storage.backend:local}") String configuredBackend) {
        return ImportStorageBackend.parse(configuredBackend);
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(prefix = "app.import.storage", name = "backend", havingValue = "r2")
    S3Client r2S3Client(R2StorageProperties properties, Environment environment) {
        URI endpoint = properties.validatedEndpoint(environment.acceptsProfiles("prod"));
        return S3Client.builder()
                .endpointOverride(endpoint)
                .region(Region.of(properties.getRegion()))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(
                        properties.getAccessKeyId(), properties.getSecretAccessKey())))
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(true)
                        .chunkedEncodingEnabled(false)
                        .build())
                .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
                .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
                .overrideConfiguration(override -> override
                        .apiCallTimeout(Duration.ofMillis(properties.getOperationTimeoutMs()))
                        .apiCallAttemptTimeout(Duration.ofMillis(properties.getAttemptTimeoutMs()))
                        .retryStrategy(retries -> retries.maxAttempts(properties.getMaxAttempts())))
                .httpClientBuilder(UrlConnectionHttpClient.builder()
                        .connectionTimeout(Duration.ofMillis(properties.getConnectionTimeoutMs()))
                        .socketTimeout(Duration.ofMillis(properties.getSocketTimeoutMs())))
                .build();
    }

    @Bean(destroyMethod = "shutdownNow")
    @ConditionalOnProperty(prefix = "app.import.storage", name = "backend", havingValue = "r2")
    ScheduledExecutorService r2ResponseDeadlineScheduler() {
        ThreadFactory threadFactory = runnable -> {
            Thread thread = new Thread(runnable, "watchwise-r2-response-deadline");
            thread.setDaemon(true);
            return thread;
        };
        ScheduledThreadPoolExecutor scheduler = new ScheduledThreadPoolExecutor(1, threadFactory);
        scheduler.setRemoveOnCancelPolicy(true);
        return scheduler;
    }

    @Bean
    @ConditionalOnProperty(prefix = "app.import.storage", name = "backend", havingValue = "r2")
    ImportFileStore r2ImportFileStore(S3Client r2S3Client, R2StorageProperties properties,
                                      ImportTemporaryFiles temporaryFiles, ImportLimits limits,
                                      ScheduledExecutorService r2ResponseDeadlineScheduler) {
        return new R2ImportFileStore(r2S3Client, properties, temporaryFiles, limits,
                r2ResponseDeadlineScheduler);
    }
}
