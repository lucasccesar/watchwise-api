package com.watchwise.watchwise_api.platformimport.config;

import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

@Component
@Validated
@ConfigurationProperties(prefix = "app.import.worker")
public class ImportWorkerProperties {
    @Min(1)
    private long fixedDelayMs = 1000;

    public long fixedDelayMs() {
        return fixedDelayMs;
    }

    public long getFixedDelayMs() {
        return fixedDelayMs;
    }

    public void setFixedDelayMs(long fixedDelayMs) {
        this.fixedDelayMs = fixedDelayMs;
    }
}
