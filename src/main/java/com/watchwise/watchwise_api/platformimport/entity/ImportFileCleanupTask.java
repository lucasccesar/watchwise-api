package com.watchwise.watchwise_api.platformimport.entity;
import jakarta.persistence.*; import lombok.*; import java.time.LocalDateTime; import java.util.UUID;
@Entity @Table(name="import_file_cleanup_tasks") @Getter @Builder @NoArgsConstructor(access=AccessLevel.PROTECTED) @AllArgsConstructor
public class ImportFileCleanupTask {
 @Id @GeneratedValue(strategy=GenerationType.UUID) @Setter private UUID id;
 @Column(name="storage_key", nullable=false, length=500) private String storageKey;
 @Column(name="ready_at", nullable=false) private LocalDateTime readyAt;
 @Column(nullable=false) private int attempts;
 @Column(name="created_at", nullable=false) private LocalDateTime createdAt;
}
