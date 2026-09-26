package com.watchwise.watchwise_api.contentposter.entity;

import com.watchwise.watchwise_api.content.entity.Content;
import com.watchwise.watchwise_api.user.entity.User;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(
        name = "user_content_posters",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_user_content_posters_user_id_content_id",
                columnNames = {"user_id", "content_id"}))
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class UserContentPoster {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Setter
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "content_id", nullable = false)
    private Content content;

    @Column(name = "custom_poster_url", nullable = false, length = 2048)
    @Setter
    private String customPosterUrl;

    @Column(name = "created_at", nullable = false)
    @Setter
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    @Setter
    private LocalDateTime updatedAt;
}
