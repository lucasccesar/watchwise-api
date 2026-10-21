package com.watchwise.watchwise_api.platformimport.entity;
import jakarta.persistence.*;
import com.watchwise.watchwise_api.platformimport.model.ImportRecordKind;
import com.watchwise.watchwise_api.platformimport.model.ImportDecision;
import lombok.*;
import java.util.UUID;
@Entity @Table(name="import_items") @Getter @Builder @NoArgsConstructor(access=AccessLevel.PROTECTED) @AllArgsConstructor
public class ImportItem {
 @Id @GeneratedValue(strategy=GenerationType.UUID) @Setter private UUID id;
 @Column(name="job_id", nullable=false) private UUID jobId;
 @Column(name="plan_generation", nullable=false) private long planGeneration;
 @Column(name="source_path", length=1000) private String sourcePath;
 @Column(name="record_locator", length=1000) private String recordLocator;
 @Enumerated(EnumType.STRING) @Column(name="normalized_kind", nullable=false, length=40) private ImportRecordKind normalizedKind;
 @Column(name="source_namespace", nullable=false, length=255) private String sourceNamespace;
 @Column(name="object_kind", nullable=false, length=255) private String objectKind;
 @Column(name="source_id", nullable=false, length=1000) private String sourceId;
 @Column(name="source_event_key", length=1200) private String sourceEventKey;
 @Column(name="parent_source_id", length=1000) private String parentSourceId;
 @Column(name="normalized_content_key", length=1000) private String normalizedContentKey;
 @Column(name="resolved_content_key", length=1000) private String resolvedContentKey;
 @Column(name="parser_version", length=100) private String parserVersion;
 @Column(name="resolved_target_json", columnDefinition="TEXT") private String resolvedTargetJson;
 @Column(name="payload_json", columnDefinition="TEXT") private String payloadJson;
 @Enumerated(EnumType.STRING) @Column(length=40) private ImportDecision decision;
 @Column(length=40) private String outcome;
 @Column(columnDefinition="TEXT") private String issue;
 @Column(nullable=false) private long ordinal;
}
