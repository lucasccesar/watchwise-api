package com.watchwise.watchwise_api.platformimport.entity;
import jakarta.persistence.*; import lombok.*; import java.time.LocalDateTime; import java.util.UUID;
@Entity @Table(name="letterboxd_seed_loads") @Getter @Builder @NoArgsConstructor(access=AccessLevel.PROTECTED) @AllArgsConstructor
public class LetterboxdSeedLoad {
 @Id @GeneratedValue(strategy=GenerationType.UUID) @Setter private UUID id;
 @Column(name="seed_version", nullable=false) private String seedVersion;
 @Column(nullable=false) private String checksum;
 @Column(name="loaded_at", nullable=false) private LocalDateTime loadedAt;
}
