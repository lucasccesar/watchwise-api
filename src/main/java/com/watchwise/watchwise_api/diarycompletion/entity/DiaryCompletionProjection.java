package com.watchwise.watchwise_api.diarycompletion.entity;

import com.watchwise.watchwise_api.content.entity.ContentType;
import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "diary_completion_projections")
@Getter @Builder @NoArgsConstructor(access = AccessLevel.PROTECTED) @AllArgsConstructor
public class DiaryCompletionProjection {
    @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
    @Column(name = "user_id", nullable = false) private UUID userId;
    @Column(name = "content_id", nullable = false) private UUID contentId;
    @Enumerated(EnumType.STRING) @Column(name = "content_type", nullable = false) private ContentType contentType;
    @Column(name = "watch_number", nullable = false) private Integer watchNumber;
    @Column(name = "anchor_diary_entry_id") private UUID anchorDiaryEntryId;
    @Column(name = "completed_on") private LocalDate completedOn;
    @Column(name = "ordered_at", nullable = false) private Instant orderedAt;
    @Column(name = "history_revision", nullable = false) private Long historyRevision;
    @Column(name = "schedule_version", nullable = false) private Long scheduleVersion;
    @Column(name = "valid_until") private Instant validUntil;
    @Version private Long version;
}
