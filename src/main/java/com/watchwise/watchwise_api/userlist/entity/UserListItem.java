package com.watchwise.watchwise_api.userlist.entity;

import com.watchwise.watchwise_api.content.entity.Content;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "user_list_items")
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class UserListItem {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Setter
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_list_id", nullable = false)
    private UserList userList;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "content_id")
    private Content content;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "child_list_id")
    private UserList childList;

    @Column(nullable = false)
    @Setter
    private Integer position;

    @Column(length = 400)
    @Setter
    private String description;

    @Column(name = "created_at", nullable = false)
    @Setter
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    @Setter
    private LocalDateTime updatedAt;
}
