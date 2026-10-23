package com.watchwise.watchwise_api.contentrating.entity;

import com.watchwise.watchwise_api.content.entity.ContentType;
import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "content_ratings")
@Getter @Builder @NoArgsConstructor(access = AccessLevel.PROTECTED) @AllArgsConstructor
public class ContentRating {
    @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
    @Column(name = "user_id", nullable = false) private UUID userId;
    @Column(name = "content_id", nullable = false) private UUID contentId;
    @Enumerated(EnumType.STRING) @Column(name = "content_type", nullable = false) private ContentType contentType;
    @Setter @Column(nullable = false) private Integer score;
    @Version private Long version;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    @Setter @Column(name = "updated_at", nullable = false) private Instant updatedAt;
}
