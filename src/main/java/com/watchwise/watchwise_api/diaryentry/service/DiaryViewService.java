package com.watchwise.watchwise_api.diaryentry.service;

import com.watchwise.watchwise_api.diaryentry.dto.DiaryViewFilter;
import com.watchwise.watchwise_api.diaryentry.dto.DiaryViewItemDTO;
import com.watchwise.watchwise_api.diaryentry.dto.DiaryViewResponseItemDTO;
import com.watchwise.watchwise_api.diaryentry.repository.DiaryViewRepository;
import com.watchwise.watchwise_api.common.dto.CursorPageResponseDTO;
import org.springframework.data.domain.Page;

import java.util.List;
import java.util.UUID;

public interface DiaryViewService {

    CursorPageResponseDTO<DiaryViewResponseItemDTO> getGroupedView(UUID viewerId, UUID ownerId,
            DiaryViewFilter filter, String cursor, Integer pageSize);

    Page<DiaryViewResponseItemDTO> getView(UUID viewerId, UUID ownerId, DiaryViewFilter filter,
            Integer pageNumber, Integer pageSize, boolean grouped);

    Page<DiaryViewItemDTO> getView(
            UUID viewerId,
            UUID ownerId,
            DiaryViewFilter filter,
            Integer pageNumber,
            Integer pageSize);

    Page<DiaryViewRepository.DiaryViewKey> getTimelineKeys(
            UUID viewerId, UUID ownerId, DiaryViewFilter filter, Integer pageNumber, Integer pageSize);

    Page<DiaryViewItemDTO> enrichDerivedCompletions(
            UUID ownerId, List<DiaryViewRepository.DiaryViewKey> keys);
}
