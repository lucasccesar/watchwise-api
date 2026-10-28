package com.watchwise.watchwise_api.platformimport.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.util.Locale;

@ConfigurationProperties(prefix = "app.import.r2")
public class R2StorageProperties {
    private String endpoint;
    private String bucket;
    private String accessKeyId;
    private String secretAccessKey;
    private String region = "auto";
    private int connectionTimeoutMs = 3000;
    private int socketTimeoutMs = 5000;
    private int attemptTimeoutMs = 10000;
    private int operationTimeoutMs = 20000;
    private int maxAttempts = 2;

    public URI validatedEndpoint(boolean production) {
        require(endpoint, "app.import.r2.endpoint");
        require(bucket, "app.import.r2.bucket");
        require(accessKeyId, "app.import.r2.access-key-id");
        require(secretAccessKey, "app.import.r2.secret-access-key");
        if (!bucket.matches("[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]")) {
            throw invalid("app.import.r2.bucket");
        }
        if (!"auto".equals(region)) throw invalid("app.import.r2.region");

        final URI parsed;
        try {
            parsed = URI.create(endpoint);
        } catch (IllegalArgumentException exception) {
            throw invalid("app.import.r2.endpoint");
        }
        String scheme = parsed.getScheme();
        String host = parsed.getHost();
        if (scheme == null || host == null || parsed.getRawUserInfo() != null || parsed.getRawQuery() != null
                || parsed.getRawFragment() != null || (parsed.getRawPath() != null && !parsed.getRawPath().isEmpty())
                || parsed.getPort() == 0 || parsed.getPort() < -1) {
            throw invalid("app.import.r2.endpoint");
        }
        boolean https = "https".equalsIgnoreCase(scheme);
        boolean loopbackHttp = "http".equalsIgnoreCase(scheme)
                && ("localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host) || "[::1]".equals(host));
        if (!https && (production || !loopbackHttp)) throw invalid("app.import.r2.endpoint");

        if (connectionTimeoutMs <= 0 || socketTimeoutMs <= 0 || attemptTimeoutMs <= 0
                || operationTimeoutMs <= 0 || maxAttempts < 1 || maxAttempts > 2
                || attemptTimeoutMs > operationTimeoutMs || connectionTimeoutMs > operationTimeoutMs
                || socketTimeoutMs > operationTimeoutMs) {
            throw invalid("app.import.r2 timeouts or max-attempts");
        }
        return parsed;
    }

    private void require(String value, String property) {
        if (value == null || value.isBlank()) throw invalid(property);
    }

    private IllegalStateException invalid(String property) {
        return new IllegalStateException("Invalid or missing " + property);
    }

    public String getEndpoint() { return endpoint; }
    public void setEndpoint(String endpoint) { this.endpoint = endpoint; }
    public String getBucket() { return bucket; }
    public void setBucket(String bucket) { this.bucket = bucket; }
    public String getAccessKeyId() { return accessKeyId; }
    public void setAccessKeyId(String accessKeyId) { this.accessKeyId = accessKeyId; }
    public String getSecretAccessKey() { return secretAccessKey; }
    public void setSecretAccessKey(String secretAccessKey) { this.secretAccessKey = secretAccessKey; }
    public String getRegion() { return region; }
    public void setRegion(String region) { this.region = region == null ? null : region.toLowerCase(Locale.ROOT); }
    public int getConnectionTimeoutMs() { return connectionTimeoutMs; }
    public void setConnectionTimeoutMs(int connectionTimeoutMs) { this.connectionTimeoutMs = connectionTimeoutMs; }
    public int getSocketTimeoutMs() { return socketTimeoutMs; }
    public void setSocketTimeoutMs(int socketTimeoutMs) { this.socketTimeoutMs = socketTimeoutMs; }
    public int getAttemptTimeoutMs() { return attemptTimeoutMs; }
    public void setAttemptTimeoutMs(int attemptTimeoutMs) { this.attemptTimeoutMs = attemptTimeoutMs; }
    public int getOperationTimeoutMs() { return operationTimeoutMs; }
    public void setOperationTimeoutMs(int operationTimeoutMs) { this.operationTimeoutMs = operationTimeoutMs; }
    public int getMaxAttempts() { return maxAttempts; }
    public void setMaxAttempts(int maxAttempts) { this.maxAttempts = maxAttempts; }
}
