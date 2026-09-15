package com.watchwise.watchwise_api.pick.repository;

import com.watchwise.watchwise_api.pick.entity.PickSelection;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PickSelectionRepository extends JpaRepository<PickSelection, UUID> {
    List<PickSelection> findByPickIdIn(Collection<UUID> pickIds);
    long countByPickId(UUID pickId);
    boolean existsByCategoryId(UUID categoryId);
    boolean existsByCategoryIdAndContentIdAndPersonTmdbIdAndContextContentId(UUID categoryId, UUID contentId, String personTmdbId, UUID contextContentId);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select selection from PickSelection selection where selection.pick.id = :pickId and selection.category.id = :categoryId")
    Optional<PickSelection> findByPickIdAndCategoryIdForUpdate(@Param("pickId") UUID pickId, @Param("categoryId") UUID categoryId);
}
