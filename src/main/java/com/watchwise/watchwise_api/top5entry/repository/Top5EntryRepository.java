package com.watchwise.watchwise_api.top5entry.repository;

import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.top5entry.entity.Top5Entry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface Top5EntryRepository extends JpaRepository<Top5Entry, UUID> {

    List<Top5Entry> findByUserIdAndTypeOrderByPositionAsc(UUID userId, ContentType type);

    @Query("""
            SELECT t FROM Top5Entry t JOIN FETCH t.content
            WHERE t.user.id = :userId AND t.type = :type
            ORDER BY t.position ASC
            """)
    List<Top5Entry> findByUserIdAndTypeWithContentOrderByPositionAsc(
            @Param("userId") UUID userId, @Param("type") ContentType type);

}