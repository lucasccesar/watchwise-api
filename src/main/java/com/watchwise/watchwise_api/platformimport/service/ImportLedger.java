package com.watchwise.watchwise_api.platformimport.service;
import com.watchwise.watchwise_api.platformimport.model.ImportSourceKey;
import com.watchwise.watchwise_api.platformimport.model.LinkReservation;
import java.util.UUID;
public interface ImportLedger {
 LinkReservation reserve(UUID ownerId, UUID jobId, ImportSourceKey key, String payloadHash);
 void markApplied(UUID ownerId, ImportSourceKey key, String targetType, UUID targetId);
 boolean isConsumed(UUID ownerId, ImportSourceKey key);
 void markSkipped(UUID ownerId, ImportSourceKey key, String targetType, UUID targetId, String reason);
 boolean completeDeferred(UUID ownerId, ImportSourceKey sourceKey, UUID deferredRecordId,
                          String targetType, UUID targetId, boolean skipped, String reason);
 void markDeletedByTarget(UUID ownerId, String targetType, UUID targetId);
 void markDeletedByTarget(String targetType, UUID targetId);
}
