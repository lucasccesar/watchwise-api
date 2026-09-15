package com.watchwise.watchwise_api.pickstemplate.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity @Table(name = "picks_template_categories") @Getter @Builder @NoArgsConstructor(access = AccessLevel.PROTECTED) @AllArgsConstructor
public class PicksTemplateCategory {
    @Id @GeneratedValue(strategy = GenerationType.UUID) @Setter private UUID id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "picks_template_id", nullable = false) private PicksTemplate picksTemplate;
    @Column(nullable = false, length = 120) @Setter private String name;
    @Column(columnDefinition = "TEXT") @Setter private String description;
    @Enumerated(EnumType.STRING) @Column(name = "category_group", nullable = false, length = 20) @Setter private PickCategoryGroup group;
    @Column(name = "display_order", nullable = false) @Setter private Integer displayOrder;
    @Enumerated(EnumType.STRING) @Column(name = "allowed_type", nullable = false, length = 20) @Setter private PickAllowedType allowedType;
    @Enumerated(EnumType.STRING) @Column(name = "option_mode", nullable = false, length = 20) @Setter private PickCategoryOptionMode optionMode;
    @Column(name = "created_at", nullable = false) @Setter private LocalDateTime createdAt;
    @Column(name = "updated_at", nullable = false) @Setter private LocalDateTime updatedAt;
}
