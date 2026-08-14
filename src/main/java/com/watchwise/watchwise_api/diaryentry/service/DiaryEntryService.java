package com.watchwise.watchwise_api.diaryentry.service;

import com.watchwise.watchwise_api.diaryentry.dto.DiaryEntryCreationDTO;
import com.watchwise.watchwise_api.diaryentry.dto.DiaryEntryResponseDTO;
import org.springframework.data.domain.Page;

import java.util.UUID;

public interface DiaryEntryService {

    Page<DiaryEntryResponseDTO> getDiaryEntries(UUID viewerId, UUID userId, Integer year, Integer pageNumber, Integer pageSize);

    DiaryEntryResponseDTO createDiaryEntry(UUID userId, DiaryEntryCreationDTO diaryEntryCreationDTO);

    DiaryEntryResponseDTO updateDiaryEntry(UUID userId, UUID diaryEntryId, DiaryEntryCreationDTO diaryEntryCreationDTO);

    void deleteDiaryEntry(UUID userId, UUID diaryEntryId);

}