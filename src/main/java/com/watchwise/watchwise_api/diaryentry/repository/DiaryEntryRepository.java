package com.watchwise.watchwise_api.diaryentry.repository;

import com.watchwise.watchwise_api.diaryentry.entity.DiaryEntry;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

public interface DiaryEntryRepository extends JpaRepository<DiaryEntry, UUID> {

    Page<DiaryEntry> findByUserIdOrderByCreatedAtDesc(UUID userId, Pageable pageable);

    Page<DiaryEntry> findByUserIdAndWatchedDateBetweenOrderByCreatedAtDesc(
            UUID userId, LocalDate watchedDateStart, LocalDate watchedDateEnd, Pageable pageable);

    Optional<DiaryEntry> findFirstByUserIdAndContentIdOrderByCreatedAtDesc(UUID userId, UUID contentId);

}