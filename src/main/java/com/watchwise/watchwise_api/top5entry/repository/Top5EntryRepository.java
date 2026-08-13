package com.watchwise.watchwise_api.top5entry.repository;

import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.top5entry.entity.Top5Entry;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface Top5EntryRepository extends JpaRepository<Top5Entry, UUID> {

    List<Top5Entry> findByUserIdAndTypeOrderByPositionAsc(UUID userId, ContentType type);

}