package com.watchwise.watchwise_api.diarycompletion.entity;

import jakarta.persistence.*;
import lombok.*;
import java.util.UUID;

@Entity
@Table(name = "legacy_diary_migration_maps")
@Getter @Builder @NoArgsConstructor(access = AccessLevel.PROTECTED) @AllArgsConstructor
public class LegacyDiaryMigrationMap {
    @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
    @Column(name = "old_diary_entry_id", nullable = false) private UUID oldDiaryEntryId;
    @Column(name = "user_id", nullable = false) private UUID userId;
    @Column(name = "destination_kind", nullable = false) private String destinationKind;
    @Column(name = "destination_id", nullable = false) private UUID destinationId;
    @Version private Long version;
}
