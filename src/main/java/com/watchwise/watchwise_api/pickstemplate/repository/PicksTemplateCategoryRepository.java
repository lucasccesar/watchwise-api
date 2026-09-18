package com.watchwise.watchwise_api.pickstemplate.repository;

import com.watchwise.watchwise_api.pickstemplate.entity.PicksTemplateCategory;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

public interface PicksTemplateCategoryRepository extends JpaRepository<PicksTemplateCategory, UUID> {
    List<PicksTemplateCategory> findByPicksTemplateIdOrderByGroupAscDisplayOrderAsc(UUID picksTemplateId);
    @Query("select category from PicksTemplateCategory category where category.picksTemplate.id in :templateIds "
            + "order by category.picksTemplate.id, category.group, category.displayOrder, category.id")
    List<PicksTemplateCategory> findByPicksTemplateIdInOrderByDisplayOrder(@Param("templateIds") Collection<UUID> templateIds);
    Optional<PicksTemplateCategory> findByIdAndPicksTemplateId(UUID id, UUID picksTemplateId);
    boolean existsByPicksTemplateId(UUID picksTemplateId);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select category from PicksTemplateCategory category where category.id = :id")
    Optional<PicksTemplateCategory> findByIdForUpdate(@Param("id") UUID id);
}
