package com.watchwise.watchwise_api.diaryentry.service;

import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.diaryentry.dto.DeletionImpactDTO;
import com.watchwise.watchwise_api.diaryentry.dto.DiaryEntryBulkCreationDTO;
import com.watchwise.watchwise_api.diaryentry.dto.DiaryEntryCreationDTO;
import com.watchwise.watchwise_api.diaryentry.dto.DiaryEntryCreationResultDTO;
import com.watchwise.watchwise_api.diaryentry.dto.DiaryEntryResponseDTO;
import com.watchwise.watchwise_api.diaryentry.dto.DiaryEntryUpdateDTO;
import com.watchwise.watchwise_api.diaryentry.dto.SeriesInProgressResponseDTO;
import org.springframework.data.domain.Page;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface DiaryEntryService {

    Page<DiaryEntryResponseDTO> getDiaryEntries(UUID viewerId, UUID userId, Integer year, Integer pageNumber, Integer pageSize,
            ContentType type, LocalDate dateFrom, LocalDate dateTo, Boolean hasReview);

    Page<SeriesInProgressResponseDTO> getSeriesInProgress(UUID viewerId, UUID userId, Integer pageNumber, Integer pageSize);

    Page<DiaryEntryResponseDTO> getReviewsForContent(UUID viewerId, UUID contentId, Integer pageNumber, Integer pageSize);

    DiaryEntryCreationResultDTO createDiaryEntry(UUID userId, DiaryEntryCreationDTO diaryEntryCreationDTO);

    List<DiaryEntryResponseDTO> createDiaryEntriesInBulk(UUID userId, DiaryEntryBulkCreationDTO diaryEntryBulkCreationDTO);

    DiaryEntryResponseDTO updateDiaryEntry(UUID userId, UUID diaryEntryId, DiaryEntryUpdateDTO diaryEntryUpdateDTO);

    void deleteDiaryEntry(UUID userId, UUID diaryEntryId, boolean overrideProtectedEntries);

    DeletionImpactDTO computeDeletionImpact(UUID userId, UUID diaryEntryId, boolean overrideProtectedEntries);

}
