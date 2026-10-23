package com.watchwise.watchwise_api.diaryentry.service;

import com.watchwise.watchwise_api.diaryentry.dto.DiaryViewFilter;
import com.watchwise.watchwise_api.diaryentry.dto.DiaryViewItemDTO;
import org.springframework.data.domain.Page;

import java.util.UUID;

public interface DiaryViewService {

    Page<DiaryViewItemDTO> getView(
            UUID viewerId,
            UUID ownerId,
            DiaryViewFilter filter,
            Integer pageNumber,
            Integer pageSize);
}
