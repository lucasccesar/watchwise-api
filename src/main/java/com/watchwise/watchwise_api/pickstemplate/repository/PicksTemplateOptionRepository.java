package com.watchwise.watchwise_api.pickstemplate.repository;

import com.watchwise.watchwise_api.pickstemplate.entity.PicksTemplateOption;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;
import java.util.Collection;

public interface PicksTemplateOptionRepository extends JpaRepository<PicksTemplateOption, UUID> {
    List<PicksTemplateOption> findByCategoryId(UUID categoryId);
    List<PicksTemplateOption> findByCategoryIdIn(Collection<UUID> categoryIds);
    Page<PicksTemplateOption> findByCategoryId(UUID categoryId, Pageable pageable);
    boolean existsByCategoryIdAndContentIdAndPersonTmdbIdAndContextContentId(UUID categoryId, UUID contentId, String personTmdbId, UUID contextContentId);
}
