package com.watchwise.watchwise_api.platformimport.entity;
import com.watchwise.watchwise_api.platformimport.model.ImportJobState;
import com.watchwise.watchwise_api.platformimport.model.ImportInputKind;
import com.watchwise.watchwise_api.platformimport.model.ImportSourceFormat;
import com.watchwise.watchwise_api.platformimport.model.ImportPlatform;
import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;
import java.util.UUID;
@Entity @Table(name="import_jobs") @Getter @Builder @NoArgsConstructor(access=AccessLevel.PROTECTED) @AllArgsConstructor
public class ImportJob {
 @Id @GeneratedValue(strategy=GenerationType.UUID) @Setter private UUID id;
 @Column(name="user_id", nullable=false) private UUID userId;
 @Enumerated(EnumType.STRING) @Column(name="input_kind", nullable=false) private ImportInputKind inputKind;
 @Enumerated(EnumType.STRING) @Column(name="source_format", nullable=false) private ImportSourceFormat sourceFormat;
 @Enumerated(EnumType.STRING) @Column(nullable=false) private ImportPlatform platform;
 @Column(name="source_account_id") private String sourceAccountId;
 @Column(name="source_namespace", nullable=false) private String sourceNamespace;
 @Enumerated(EnumType.STRING) @Column(nullable=false) private ImportJobState state;
 @Version @Column(nullable=false) private long version;
 @Column(name="preview_version", nullable=false) private long previewVersion;
 @Column(name="selection_json", nullable=false, columnDefinition="TEXT") private String selectionJson;
 @Column(name="zip_digest") private String zipDigest;
 @Column(nullable=false) private String timezone;
 @Column(name="storage_key", nullable=false) private String storageKey;
 @Column(columnDefinition="TEXT") private String checkpoint;
 @Column(name="work_phase") private String workPhase;
 @Column(name="work_pending", nullable=false) private boolean workPending;
 @Column(name="next_run_at") private LocalDateTime nextRunAt;
 @Column(name="lease_token") private UUID leaseToken;
 @Column(name="lease_until") private LocalDateTime leaseUntil;
 @Column(name="created_at", nullable=false) private LocalDateTime createdAt;
 @Column(name="updated_at", nullable=false) private LocalDateTime updatedAt;
 @Column(name="expires_at") private LocalDateTime expiresAt;
}
