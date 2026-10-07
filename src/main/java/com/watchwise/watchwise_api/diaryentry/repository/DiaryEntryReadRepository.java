package com.watchwise.watchwise_api.diaryentry.repository;

import com.watchwise.watchwise_api.diaryentry.entity.DiaryEntry;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface DiaryEntryReadRepository {

    Page<DiaryEntry> findPage(DiaryEntrySearchCriteria criteria, DiaryEntrySort sort, Pageable pageable);
}
