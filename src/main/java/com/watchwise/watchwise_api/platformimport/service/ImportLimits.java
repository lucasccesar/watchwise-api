package com.watchwise.watchwise_api.platformimport.service;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app.import")
public class ImportLimits {
    private long maxUploadBytes = 50L * 1024 * 1024;
    private long maxTemplateUploadBytes = 5L * 1024 * 1024;
    private long maxExpandedBytes = 512L * 1024 * 1024;
    private long maxEntryBytes = 64L * 1024 * 1024;
    private int maxEntries = 2000;
    private long maxRecords = 500000;
    private long maxFieldBytes = 1024 * 1024;
    private long maxCsvRecordBytes = 8L * 1024 * 1024;
    private int maxCsvColumnsPerRecord = 256;
    private int maxJsonDepth = 32;
    private int maxPendingJobsPerUser = 3;
    private long maxPendingUploadBytesPerUser = 157286400;
    private long maxPendingUploadBytesGlobal = 2147483648L;
    private int maxConcurrentUploadsPerProcess = 2;
    private int maxConcurrentTmdbSearchesPerProcess = 4;

    public ImportLimits() {}

    public ImportLimits(long maxUploadBytes, long maxExpandedBytes, long maxEntryBytes,
                        int maxEntries, long maxRecords, long maxFieldBytes, int maxJsonDepth,
                        int maxPendingJobsPerUser, long maxPendingUploadBytesPerUser,
                        long maxPendingUploadBytesGlobal, int maxConcurrentUploadsPerProcess) {
        this.maxUploadBytes = maxUploadBytes;
        this.maxExpandedBytes = maxExpandedBytes;
        this.maxEntryBytes = maxEntryBytes;
        this.maxEntries = maxEntries;
        this.maxRecords = maxRecords;
        this.maxFieldBytes = maxFieldBytes;
        this.maxJsonDepth = maxJsonDepth;
        this.maxPendingJobsPerUser = maxPendingJobsPerUser;
        this.maxPendingUploadBytesPerUser = maxPendingUploadBytesPerUser;
        this.maxPendingUploadBytesGlobal = maxPendingUploadBytesGlobal;
        this.maxConcurrentUploadsPerProcess = maxConcurrentUploadsPerProcess;
    }

    public long maxUploadBytes() { return maxUploadBytes; }
    public long maxTemplateUploadBytes() { return maxTemplateUploadBytes; }
    public long maxExpandedBytes() { return maxExpandedBytes; }
    public long maxEntryBytes() { return maxEntryBytes; }
    public int maxEntries() { return maxEntries; }
    public long maxRecords() { return maxRecords; }
    public long maxFieldBytes() { return maxFieldBytes; }
    public long maxCsvRecordBytes() { return maxCsvRecordBytes; }
    public int maxCsvColumnsPerRecord() { return maxCsvColumnsPerRecord; }
    public int maxJsonDepth() { return maxJsonDepth; }
    public int maxPendingJobsPerUser() { return maxPendingJobsPerUser; }
    public long maxPendingUploadBytesPerUser() { return maxPendingUploadBytesPerUser; }
    public long maxPendingUploadBytesGlobal() { return maxPendingUploadBytesGlobal; }
    public int maxConcurrentUploadsPerProcess() { return maxConcurrentUploadsPerProcess; }
    public int maxConcurrentTmdbSearchesPerProcess() { return maxConcurrentTmdbSearchesPerProcess; }

    public void setMaxUploadBytes(long value) { maxUploadBytes = value; }
    public void setMaxTemplateUploadBytes(long value) { maxTemplateUploadBytes = value; }
    public void setMaxExpandedBytes(long value) { maxExpandedBytes = value; }
    public void setMaxEntryBytes(long value) { maxEntryBytes = value; }
    public void setMaxEntries(int value) { maxEntries = value; }
    public void setMaxRecords(long value) { maxRecords = value; }
    public void setMaxFieldBytes(long value) { maxFieldBytes = value; }
    public void setMaxCsvRecordBytes(long value) { maxCsvRecordBytes = value; }
    public void setMaxCsvColumnsPerRecord(int value) { maxCsvColumnsPerRecord = value; }
    public void setMaxJsonDepth(int value) { maxJsonDepth = value; }
    public void setMaxPendingJobsPerUser(int value) { maxPendingJobsPerUser = value; }
    public void setMaxPendingUploadBytesPerUser(long value) { maxPendingUploadBytesPerUser = value; }
    public void setMaxPendingUploadBytesGlobal(long value) { maxPendingUploadBytesGlobal = value; }
    public void setMaxConcurrentUploadsPerProcess(int value) { maxConcurrentUploadsPerProcess = value; }
    public void setMaxConcurrentTmdbSearchesPerProcess(int value) { maxConcurrentTmdbSearchesPerProcess = value; }

    public long getMaxUploadBytes() { return maxUploadBytes; }
    public long getMaxTemplateUploadBytes() { return maxTemplateUploadBytes; }
    public long getMaxExpandedBytes() { return maxExpandedBytes; }
    public long getMaxEntryBytes() { return maxEntryBytes; }
    public int getMaxEntries() { return maxEntries; }
    public long getMaxRecords() { return maxRecords; }
    public long getMaxFieldBytes() { return maxFieldBytes; }
    public long getMaxCsvRecordBytes() { return maxCsvRecordBytes; }
    public int getMaxCsvColumnsPerRecord() { return maxCsvColumnsPerRecord; }
    public int getMaxJsonDepth() { return maxJsonDepth; }
    public int getMaxPendingJobsPerUser() { return maxPendingJobsPerUser; }
    public long getMaxPendingUploadBytesPerUser() { return maxPendingUploadBytesPerUser; }
    public long getMaxPendingUploadBytesGlobal() { return maxPendingUploadBytesGlobal; }
    public int getMaxConcurrentUploadsPerProcess() { return maxConcurrentUploadsPerProcess; }
    public int getMaxConcurrentTmdbSearchesPerProcess() { return maxConcurrentTmdbSearchesPerProcess; }
}
