package com.watchwise.watchwise_api.contentreview.entity;

import com.watchwise.watchwise_api.content.entity.ContentType;
import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "content_reviews")
@Getter @Builder @NoArgsConstructor(access = AccessLevel.PROTECTED) @AllArgsConstructor
public class ContentReview {
    @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
    @Column(name = "user_id", nullable = false) private UUID userId;
    @Column(name = "content_id", nullable = false) private UUID contentId;
    @Enumerated(EnumType.STRING) @Column(name = "content_type", nullable = false) private ContentType contentType;
    @Enumerated(EnumType.STRING) @Column(name = "publication_kind", nullable = false) private ReviewPublicationKind publicationKind;
    @Enumerated(EnumType.STRING) @Column(name = "context_kind", nullable = false) private ReviewContextKind contextKind;
    @Column(name = "context_json", columnDefinition = "TEXT") private String contextJson;
    @Setter @Column(columnDefinition = "TEXT") private String comment;
    @Column(name = "score_at_publication", updatable = false) private Integer scoreAtPublication;
    @Column(name = "watch_number") private Integer watchNumber;
    @Column(name = "source_published_at") private Instant sourcePublishedAt;
    @Column(name = "source_published_on") private LocalDate sourcePublishedOn;
    @Version private Long version;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    @Setter @Column(name = "updated_at", nullable = false) private Instant updatedAt;
}
