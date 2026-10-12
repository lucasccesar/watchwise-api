package com.watchwise.watchwise_api.pickstemplate.repository;

import com.watchwise.watchwise_api.pickstemplate.entity.PicksTemplateOption;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;
import java.util.Collection;

public interface PicksTemplateOptionRepository extends JpaRepository<PicksTemplateOption, UUID> {
    @Query("""
            select option from PicksTemplateOption option
            left join fetch option.content
            left join fetch option.contextContent
            where option.category.id = :categoryId
            """)
    List<PicksTemplateOption> findByCategoryId(@Param("categoryId") UUID categoryId);

    @Query("""
            select option from PicksTemplateOption option
            left join fetch option.content
            left join fetch option.contextContent
            where option.category.id in :categoryIds
            """)
    List<PicksTemplateOption> findByCategoryIdIn(@Param("categoryIds") Collection<UUID> categoryIds);

    @Query(value = """
            select option from PicksTemplateOption option
            left join fetch option.content
            left join fetch option.contextContent
            where option.category.id = :categoryId
            """, countQuery = "select count(option) from PicksTemplateOption option where option.category.id = :categoryId")
    Page<PicksTemplateOption> findByCategoryId(@Param("categoryId") UUID categoryId, Pageable pageable);
    boolean existsByCategoryIdAndContentIdAndPersonTmdbIdAndContextContentId(UUID categoryId, UUID contentId, String personTmdbId, UUID contextContentId);
}
