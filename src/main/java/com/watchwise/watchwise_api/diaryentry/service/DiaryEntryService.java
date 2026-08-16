package com.watchwise.watchwise_api.diaryentry.service;

import com.watchwise.watchwise_api.diaryentry.dto.DiaryEntryBulkCreationDTO;
import com.watchwise.watchwise_api.diaryentry.dto.DiaryEntryCreationDTO;
import com.watchwise.watchwise_api.diaryentry.dto.DiaryEntryResponseDTO;
import com.watchwise.watchwise_api.diaryentry.dto.DiaryEntryUpdateDTO;
import com.watchwise.watchwise_api.diaryentry.dto.DeletionImpactDTO;
import org.springframework.data.domain.Page;

import java.util.List;
import java.util.UUID;

public interface DiaryEntryService {

    Page<DiaryEntryResponseDTO> getDiaryEntries(UUID viewerId, UUID userId, Integer year, Integer pageNumber, Integer pageSize);

    DiaryEntryResponseDTO createDiaryEntry(UUID userId, DiaryEntryCreationDTO diaryEntryCreationDTO);

    List<DiaryEntryResponseDTO> createDiaryEntriesInBulk(UUID userId, DiaryEntryBulkCreationDTO diaryEntryBulkCreationDTO);

    DiaryEntryResponseDTO updateDiaryEntry(UUID userId, UUID diaryEntryId, DiaryEntryUpdateDTO diaryEntryUpdateDTO);

    void deleteDiaryEntry(UUID userId, UUID diaryEntryId, boolean overrideProtectedEntries);

    DeletionImpactDTO computeDeletionImpact(UUID userId, UUID diaryEntryId);

}