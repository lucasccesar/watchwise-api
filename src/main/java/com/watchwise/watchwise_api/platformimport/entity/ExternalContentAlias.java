package com.watchwise.watchwise_api.platformimport.entity;
import com.watchwise.watchwise_api.platformimport.model.AliasTrust;
import com.watchwise.watchwise_api.platformimport.model.ImportTarget;
import com.watchwise.watchwise_api.content.dto.MediaReference;
import com.watchwise.watchwise_api.content.entity.ContentType;
import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;
import java.util.UUID;
@Entity @Table(name="external_content_aliases") @Getter @Builder @NoArgsConstructor(access=AccessLevel.PROTECTED) @AllArgsConstructor
public class ExternalContentAlias {
 @Id @GeneratedValue(strategy=GenerationType.UUID) @Setter private UUID id;
 @Column(name="user_id") private UUID userId;
 @Column(name="scope_key", nullable=false) private String scopeKey;
 @Column(name="target_kind", nullable=false) private String targetKind;
 @Column(name="original_title", nullable=false) private String originalTitle;
 @Column(name="normalized_title", nullable=false) private String normalizedTitle;
 @Column(name="release_year") private Integer releaseYear;
 @Column(name="target_key", nullable=false) private String targetKey;
 @Column(name="target_type", nullable=false) private String targetType;
 @Column(name="target_tmdb_id") private String targetTmdbId;
 @Column(name="series_tmdb_id") private String seriesTmdbId;
 @Column(name="season_number") private Integer seasonNumber;
 @Column(name="episode_number") private Integer episodeNumber;
 @Column(name="person_tmdb_id") private String personTmdbId;
 @Column(nullable=false) private String provenance;
 @Enumerated(EnumType.STRING) @Column(nullable=false) private AliasTrust trust;
 @Column(name="seed_version") private String seedVersion;
 @Column(name="created_at", nullable=false) private LocalDateTime createdAt;
 @Column(name="updated_at", nullable=false) private LocalDateTime updatedAt;

 @Transient
 public ImportTarget asImportTarget() {
  if ("PERSON".equals(targetKind)) return new ImportTarget.PersonTarget(personTmdbId, null);
  ContentType type = ContentType.valueOf(targetType);
  MediaReference reference = switch (type) {
   case MOVIE, SERIES -> new MediaReference(type, targetTmdbId, null, null, null);
   case SEASON -> new MediaReference(type, null, seriesTmdbId, seasonNumber, null);
   case EPISODE -> new MediaReference(type, null, seriesTmdbId, seasonNumber, episodeNumber);
  };
  return new ImportTarget.ContentTarget(reference);
 }
}
