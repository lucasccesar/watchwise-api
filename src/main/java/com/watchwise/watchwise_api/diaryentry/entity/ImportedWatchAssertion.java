package com.watchwise.watchwise_api.diaryentry.entity;
import com.watchwise.watchwise_api.content.entity.Content; import com.watchwise.watchwise_api.user.entity.User;
import jakarta.persistence.*; import lombok.*; import java.time.LocalDate; import java.time.LocalDateTime; import java.util.UUID;
@Entity @Table(name="imported_watch_assertions") @Getter @Builder @NoArgsConstructor(access=AccessLevel.PROTECTED) @AllArgsConstructor
public class ImportedWatchAssertion {
 @Id @GeneratedValue(strategy=GenerationType.UUID) @Setter private UUID id;
 @ManyToOne(fetch=FetchType.LAZY, optional=false) @JoinColumn(name="user_id", nullable=false) private User user;
 @ManyToOne(fetch=FetchType.LAZY, optional=false) @JoinColumn(name="content_id", nullable=false) private Content content;
 @Column(name="source_namespace", nullable=false) private String sourceNamespace;
 @Column(name="object_kind", nullable=false) private String objectKind;
 @Column(name="source_id", nullable=false) private String sourceId;
 @Column(name="declared_date") private LocalDate declaredDate;
 @Column(name="declared_plays") private Integer declaredPlays;
 @Column(nullable=false) @Builder.Default private String coverage="UNKNOWN";
 @Column(name="created_at", nullable=false) private LocalDateTime createdAt;
}
