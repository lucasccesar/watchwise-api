package com.watchwise.watchwise_api.pickstemplate.entity;

import com.watchwise.watchwise_api.user.entity.User;
import jakarta.persistence.*;
import lombok.*;
import java.time.*;
import java.util.UUID;

@Entity @Table(name = "picks_templates") @Getter @Builder @NoArgsConstructor(access = AccessLevel.PROTECTED) @AllArgsConstructor
public class PicksTemplate {
    @Id @GeneratedValue(strategy = GenerationType.UUID) @Setter private UUID id;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "creator_id") private User creator;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20) @Setter private PickOrigin origin;
    @Column(nullable = false, length = 120) @Setter private String name;
    @Column(columnDefinition = "TEXT") @Setter private String description;
    @Column(name = "cover_image", length = 2048) @Setter private String coverImage;
    @Column(columnDefinition = "TEXT") @Setter private String instructions;
    @Column(name = "eligibility_start_date") @Setter private LocalDate eligibilityStartDate;
    @Column(name = "eligibility_end_date") @Setter private LocalDate eligibilityEndDate;
    @Column(name = "created_at", nullable = false) @Setter private LocalDateTime createdAt;
    @Column(name = "updated_at", nullable = false) @Setter private LocalDateTime updatedAt;
    @Column(name = "likes_count", nullable = false) @Setter @Builder.Default private Integer likesCount = 0;
}
