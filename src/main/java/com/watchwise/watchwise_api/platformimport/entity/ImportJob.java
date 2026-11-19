package com.watchwise.watchwise_api.platformimport.entity;
import com.watchwise.watchwise_api.platformimport.model.ImportJobState;
import com.watchwise.watchwise_api.platformimport.model.ImportInputKind;
import com.watchwise.watchwise_api.platformimport.model.ImportSourceFormat;
import com.watchwise.watchwise_api.platformimport.model.ImportPlatform;
import com.watchwise.watchwise_api.platformimport.model.ResolutionProgressPhase;
import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;
import java.util.UUID;
@Entity @Table(name="import_jobs") @Getter @Builder @NoArgsConstructor(access=AccessLevel.PROTECTED) @AllArgsConstructor
public class ImportJob {
 @Id @GeneratedValue(strategy=GenerationType.UUID) @Setter private UUID id;
 @Column(name="user_id", nullable=false) private UUID userId;
 @Enumerated(EnumType.STRING) @Column(name="input_kind", nullable=false, length=30) private ImportInputKind inputKind;
 @Enumerated(EnumType.STRING) @Column(name="source_format", nullable=false, length=40) private ImportSourceFormat sourceFormat;
 @Enumerated(EnumType.STRING) @Column(length=20) private ImportPlatform platform;
 @Setter @Column(name="source_account_id", length=255) private String sourceAccountId;
 @Setter @Column(name="source_namespace", nullable=false, length=255) private String sourceNamespace;
 @Enumerated(EnumType.STRING) @Column(nullable=false, length=40) @Setter private ImportJobState state;
 @Version @Column(nullable=false) private long version;
 @Column(name="preview_version", nullable=false) @Setter private long previewVersion;
 @Column(name="selection_json", nullable=false, columnDefinition="TEXT") @Setter private String selectionJson;
 @Setter @Column(name="zip_digest", length=128) private String zipDigest;
 @Column(nullable=false, length=100) private String timezone;
 @Column(name="storage_key", nullable=false, length=500) private String storageKey;
 @Setter @Column(name="upload_size_bytes", nullable=false) private long uploadSizeBytes;
 @Column(name="progress_total_items") private Long progressTotalItems;
 @Column(name="progress_applied_items") private Long progressAppliedItems;
 @Column(name="progress_skipped_items") private Long progressSkippedItems;
 @Enumerated(EnumType.STRING) @Column(name="resolution_progress_phase", length=32) private ResolutionProgressPhase resolutionProgressPhase;
 @Column(name="resolution_progress_generation") private Long resolutionProgressGeneration;
 @Column(name="resolution_progress_last_ordinal") private Long resolutionProgressLastOrdinal;
 @Column(name="resolution_progress_total") private Long resolutionProgressTotal;
 @Column(name="resolution_progress_examined") private Long resolutionProgressExamined;
 @Setter @Column(name="resolution_progress_legacy", nullable=false) private boolean resolutionProgressLegacy;
 @Column(name="review_required_count", nullable=false) private long reviewRequiredCount;
 @Column(name="review_revision", nullable=false) private long reviewRevision;
 @Setter @Column(columnDefinition="TEXT") private String checkpoint;
 @Column(name="work_phase", length=40) @Setter private String workPhase;
 @Column(name="work_pending", nullable=false) @Setter private boolean workPending;
 @Column(name="next_run_at") @Setter private LocalDateTime nextRunAt;
 @Setter @Column(name="lease_token") private UUID leaseToken;
 @Setter @Column(name="lease_until") private LocalDateTime leaseUntil;
 @Column(name="created_at", nullable=false) private LocalDateTime createdAt;
 @Column(name="updated_at", nullable=false) @Setter private LocalDateTime updatedAt;
 @Setter @Column(name="expires_at") private LocalDateTime expiresAt;

 public void clearApplyProgress() {
  progressTotalItems = null;
  progressAppliedItems = null;
  progressSkippedItems = null;
 }

 public void replaceApplyProgress(long totalItems, long appliedItems, long skippedItems) {
  validateApplyProgress(totalItems, appliedItems, skippedItems);
  progressTotalItems = totalItems;
  progressAppliedItems = appliedItems;
  progressSkippedItems = skippedItems;
 }

 public void adjustApplyProgress(long totalDelta, long appliedDelta, long skippedDelta) {
  if (progressTotalItems == null || progressAppliedItems == null || progressSkippedItems == null) {
   throw new IllegalStateException("Import progress has not been initialized");
  }
  replaceApplyProgress(Math.addExact(progressTotalItems, totalDelta),
          Math.addExact(progressAppliedItems, appliedDelta),
          Math.addExact(progressSkippedItems, skippedDelta));
 }

 private void validateApplyProgress(long totalItems, long appliedItems, long skippedItems) {
  if (totalItems < 0 || appliedItems < 0 || skippedItems < 0
          || appliedItems > totalItems || skippedItems > totalItems
          || appliedItems > totalItems - skippedItems) {
   throw new IllegalArgumentException("Import progress counts are inconsistent");
  }
 }

 public void initializeResolutionProgress(long generation, long lastOrdinal, long total, long examined) {
  if (generation < 0 || lastOrdinal < -1 || total < 0 || examined < 0 || examined > total) {
   throw new IllegalArgumentException("Import resolution progress counts are inconsistent");
  }
  resolutionProgressPhase = ResolutionProgressPhase.MATCHING;
  resolutionProgressLegacy = false;
  resolutionProgressGeneration = generation;
  resolutionProgressLastOrdinal = lastOrdinal;
  resolutionProgressTotal = total;
  resolutionProgressExamined = examined;
 }

 public void setResolutionProgressPhase(ResolutionProgressPhase phase) {
  if (resolutionProgressGeneration == null || phase == null) {
   throw new IllegalStateException("Import resolution progress has not been initialized");
  }
  resolutionProgressPhase = phase;
 }

 public void adjustResolutionExamined(long delta) {
  if (resolutionProgressExamined == null || resolutionProgressTotal == null) {
   throw new IllegalStateException("Import resolution progress has not been initialized");
  }
  long next = Math.addExact(resolutionProgressExamined, delta);
  if (next < 0 || next > resolutionProgressTotal) {
   throw new IllegalArgumentException("Import resolution progress counts are inconsistent");
  }
  resolutionProgressExamined = next;
 }

 public long nextReviewRevision(long reviewRequiredDelta) {
  long nextReviewCount = Math.addExact(reviewRequiredCount, reviewRequiredDelta);
  if (nextReviewCount < 0) throw new IllegalArgumentException("Review queue count is inconsistent");
  reviewRequiredCount = nextReviewCount;
  reviewRevision = Math.addExact(reviewRevision, 1);
  return reviewRevision;
 }

 public void clearResolutionProgress() {
  resolutionProgressPhase = null;
  resolutionProgressGeneration = null;
  resolutionProgressLastOrdinal = null;
  resolutionProgressTotal = null;
  resolutionProgressExamined = null;
  resolutionProgressLegacy = false;
  reviewRequiredCount = 0;
  reviewRevision = 0;
 }

}
