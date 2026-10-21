package com.watchwise.watchwise_api.platformimport.entity;
import com.watchwise.watchwise_api.platformimport.model.ImportLinkState;
import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;
import java.util.UUID;
@Entity @Table(name="external_import_links") @Getter @Builder @NoArgsConstructor(access=AccessLevel.PROTECTED) @AllArgsConstructor
public class ExternalImportLink {
 @Id @GeneratedValue(strategy=GenerationType.UUID) @Setter private UUID id;
 @Column(name="user_id", nullable=false) private UUID userId;
 @Column(name="source_namespace", nullable=false) private String sourceNamespace;
 @Column(name="object_kind", nullable=false) private String objectKind;
 @Column(name="source_id", nullable=false) private String sourceId;
 @Enumerated(EnumType.STRING) @Column(nullable=false) private ImportLinkState state;
 @Column(name="owning_job_id") private UUID owningJobId;
 @Column(name="target_type") private String targetType;
 @Column(name="target_id") private UUID targetId;
 @Column(name="payload_hash") private String payloadHash;
 @Column(name="outcome_reason", columnDefinition="TEXT") private String outcomeReason;
 @Column(name="parent_link_id") private UUID parentLinkId;
 @Column(name="created_at", nullable=false) private LocalDateTime createdAt;
 @Column(name="updated_at", nullable=false) private LocalDateTime updatedAt;
}
