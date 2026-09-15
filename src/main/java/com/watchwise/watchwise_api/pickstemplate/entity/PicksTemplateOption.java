package com.watchwise.watchwise_api.pickstemplate.entity;

import com.watchwise.watchwise_api.content.entity.Content;
import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity @Table(name = "picks_template_options") @Getter @Builder @NoArgsConstructor(access = AccessLevel.PROTECTED) @AllArgsConstructor
public class PicksTemplateOption {
    @Id @GeneratedValue(strategy = GenerationType.UUID) @Setter private UUID id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "category_id", nullable = false) private PicksTemplateCategory category;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "content_id") private Content content;
    @Column(name = "person_tmdb_id", length = 20) @Setter private String personTmdbId;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "context_content_id") private Content contextContent;
    @Column(name = "created_at", nullable = false) @Setter private LocalDateTime createdAt;
}
