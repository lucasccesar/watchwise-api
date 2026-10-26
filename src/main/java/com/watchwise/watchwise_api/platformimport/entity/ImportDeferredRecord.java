package com.watchwise.watchwise_api.platformimport.entity;
import jakarta.persistence.*; import lombok.*; import java.time.LocalDateTime; import java.util.UUID;
@Entity @Table(name="import_deferred_records") @Getter @Builder @NoArgsConstructor(access=AccessLevel.PROTECTED) @AllArgsConstructor
public class ImportDeferredRecord {
 @Id @GeneratedValue(strategy=GenerationType.UUID) @Setter private UUID id;
 @Column(name="user_id", nullable=false) private UUID userId;
 @Column(name="source_namespace", nullable=false, length=255) private String sourceNamespace;
 @Column(name="object_kind", nullable=false, length=255) private String objectKind;
 @Column(name="source_id", nullable=false, length=1000) private String sourceId;
 @Column(name="normalized_payload", nullable=false, columnDefinition="TEXT") private String normalizedPayload;
 @Setter @Column(nullable=false, columnDefinition="TEXT") private String provenance;
 @Column(name="created_at", nullable=false) private LocalDateTime createdAt;
}
