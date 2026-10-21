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
 @Column(name="source_path") private String sourcePath;
 @Column(name="record_locator") private String recordLocator;
 @Enumerated(EnumType.STRING) @Column(name="normalized_kind", nullable=false) private ImportRecordKind normalizedKind;
 @Column(name="source_namespace", nullable=false) private String sourceNamespace;
 @Column(name="object_kind", nullable=false) private String objectKind;
 @Column(name="source_id", nullable=false) private String sourceId;
 @Column(name="source_event_key") private String sourceEventKey;
 @Column(name="parent_source_id") private String parentSourceId;
 @Column(name="normalized_content_key") private String normalizedContentKey;
 @Column(name="resolved_content_key") private String resolvedContentKey;
 @Column(name="parser_version") private String parserVersion;
 @Column(name="resolved_target_json", columnDefinition="TEXT") private String resolvedTargetJson;
 @Column(name="payload_json", columnDefinition="TEXT") private String payloadJson;
 @Enumerated(EnumType.STRING) private ImportDecision decision;
 private String outcome;
 @Column(columnDefinition="TEXT") private String issue;
 @Column(nullable=false) private long ordinal;
}
