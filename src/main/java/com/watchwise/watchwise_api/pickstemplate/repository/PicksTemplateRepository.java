package com.watchwise.watchwise_api.pickstemplate.repository;

import com.watchwise.watchwise_api.pickstemplate.entity.PickOrigin;
import com.watchwise.watchwise_api.pickstemplate.entity.PicksTemplate;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface PicksTemplateRepository extends JpaRepository<PicksTemplate, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select template from PicksTemplate template where template.id = :id")
    Optional<PicksTemplate> findByIdForUpdate(@Param("id") UUID id);

    @Query("""
            select template from PicksTemplate template
            where (:origin is null or template.origin = :origin)
              and (:escapedName is null or lower(template.name) like concat('%', lower(:escapedName), '%') escape '\\')
            order by lower(template.name), template.id
            """)
    Page<PicksTemplate> search(@Param("origin") PickOrigin origin, @Param("escapedName") String escapedName, Pageable pageable);
}
