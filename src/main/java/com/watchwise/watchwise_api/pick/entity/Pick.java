package com.watchwise.watchwise_api.pick.entity;

import com.watchwise.watchwise_api.pickstemplate.entity.PicksTemplate;
import com.watchwise.watchwise_api.user.entity.User;
import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity @Table(name = "picks") @Getter @Builder @NoArgsConstructor(access = AccessLevel.PROTECTED) @AllArgsConstructor
public class Pick {
    @Id @GeneratedValue(strategy = GenerationType.UUID) @Setter private UUID id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "picks_template_id", nullable = false) private PicksTemplate picksTemplate;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "user_id", nullable = false) private User user;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20) @Setter private PickVisibility visibility;
    @Column(name = "created_at", nullable = false) @Setter private LocalDateTime createdAt;
    @Column(name = "updated_at", nullable = false) @Setter private LocalDateTime updatedAt;
}
